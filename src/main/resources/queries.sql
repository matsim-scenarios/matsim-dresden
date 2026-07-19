-- ============================================================================
-- DuckDB descriptor for a Dresden run's plan parquet files.
-- Load it and everything below is ready:   duckdb -init queries.sql
-- (run duckdb from THIS directory so the relative globs find the files).
-- Views name the files; macros are saved named queries. Add more freely.
-- ============================================================================

-- ---- views: one named handle per parquet file (globs bind to this run) -----
CREATE OR REPLACE VIEW experienced_plans AS
    SELECT * FROM read_parquet('*output_experienced_plans.parquet');

CREATE OR REPLACE VIEW output_plans AS
    SELECT * FROM read_parquet('*output_plans.parquet');
-- (future files: just add another CREATE VIEW ... here)

-- ---- derived view: experienced plans decomposed into trips -----------------
-- One row per trip per person. A "trip" is the stage chain (access/egress walk +
-- interaction activities + main leg) between two "real" (non-interaction) activities,
-- collapsed to one row. main_mode = the leg routingMode (MATSim's authoritative trip main
-- mode, identical across a trip's stage legs), falling back to the longest-distance stage's
-- mode if routingMode is absent. trav_s / dist_m accumulate over ALL stage legs. Times are
-- raw seconds -- wrap with hms() for HH:MM:SS display (see trip_chain).
CREATE OR REPLACE VIEW experienced_trips AS
    WITH elements AS (
        SELECT personId, a.sequence AS seq, TRUE AS is_act, a.actType AS name, NULL::VARCHAR AS routing_mode,
               a.startTime AS t_start, a.endTime AS t_end,
               NULL::DOUBLE AS travTime, NULL::DOUBLE AS distance,
               (a.actType NOT LIKE '%interaction%') AS is_real
        FROM experienced_plans, UNNEST(activities) AS t(a)
        UNION ALL
        SELECT personId, l.sequence, FALSE, l.mode, l.routingMode,
               NULL::DOUBLE, NULL::DOUBLE, l.travTime, l.route.distance, FALSE
        FROM experienced_plans, UNNEST(legs) AS t(l)
    ),
    ranked AS (   -- real_rank = # real activities seen so far within the person
        SELECT *, sum(CASE WHEN is_act AND is_real THEN 1 ELSE 0 END)
                      OVER (PARTITION BY personId ORDER BY seq) AS real_rank
        FROM elements
    ),
    real_acts AS (   -- the trip boundaries (rank r within person)
        SELECT personId, real_rank AS r, name AS actType, t_start, t_end
        FROM ranked WHERE is_act AND is_real
    ),
    trip_legs AS (   -- stage legs grouped into their trip (a leg's real_rank = its trip id)
        SELECT personId, real_rank AS trip_id,
               coalesce(max(routing_mode), arg_max(name, coalesce(distance, 0))) AS main_mode,
               sum(travTime) AS trav_s, sum(distance) AS dist_m,
               -- distance of the main-mode leg(s) only (name = routingMode), i.e. excluding access/egress walk
               sum(distance) FILTER (WHERE name = routing_mode) AS main_dist_m,
               list(name ORDER BY seq) AS stages
        FROM ranked WHERE NOT is_act GROUP BY personId, real_rank
    )
    SELECT a.personId, a.r AS trip,
           a.actType AS from_act, a.t_end AS depart,
           tl.main_mode, b.actType AS to_act, b.t_start AS arrive,
           tl.trav_s, tl.dist_m, tl.main_dist_m, tl.stages
    FROM real_acts a
    JOIN real_acts b ON b.personId = a.personId AND b.r = a.r + 1
    LEFT JOIN trip_legs tl ON tl.personId = a.personId AND tl.trip_id = a.r;

-- ---- derived view: experienced activities with a defined effective duration ---------------
-- One row per non-interaction activity per person WHERE the effective duration is defined:
--   eff_duration = endTime - startTime if both are set, else maxDuration.
-- Rows without a defined duration are dropped. In practice that leaves the "middle" activities:
-- the day's first/last activity lack a start/end and (in experienced plans) carry no maxDuration,
-- so they fall out. No wrap-around and no start/end interpolation is modelled here -- activities
-- stay exactly as the experienced plan has them. The three source time fields (startTime, endTime,
-- maxDuration -- raw seconds) are kept for reference. eff_duration = 0 here is a GENUINE
-- zero-duration activity (start == end), not "undefined". Format times/durations with hms().
CREATE OR REPLACE VIEW experienced_activities AS
    SELECT * FROM (
        SELECT personId, a.sequence AS seq, a.actType, a.link,
               a.startTime, a.endTime, a.maxDuration,
               CASE WHEN a.startTime IS NOT NULL AND a.endTime IS NOT NULL
                    THEN a.endTime - a.startTime
                    ELSE a.maxDuration END AS eff_duration
        FROM experienced_plans, UNNEST(activities) AS t(a)
        WHERE a.actType NOT LIKE '%interaction%'
    ) WHERE eff_duration IS NOT NULL;

-- ---- helpers ---------------------------------------------------------------

-- Seconds -> a native INTERVAL that prints as HH:MM:SS. There is no >24h TIME type in DuckDB
-- (TIME is capped at 24:00:00), but INTERVAL keeps MATSim's after-midnight convention (24:56:49,
-- 27:00:00) and is arithmetic-friendly. NB: cast to BIGINT, not the earlier printf -- a CAST to
-- INTEGER in DuckDB ROUNDS to nearest, which had corrupted the hand-formatted fields.
CREATE OR REPLACE MACRO hms(t) AS to_seconds(t::BIGINT);

-- ---- macros: saved queries -------------------------------------------------

-- Full experienced day of one person: activities and legs interleaved by sequence.
CREATE OR REPLACE MACRO plan_chain(pid) AS TABLE
    WITH p AS (SELECT * FROM experienced_plans WHERE personId = pid::VARCHAR)
    SELECT seq, kind, detail, link, hms(t_start) AS start, hms(t_end) AS "end",
           round(travTime) AS trav_s, round(distance) AS dist_m
    FROM (
        SELECT a.sequence AS seq, 'act' AS kind, a.actType AS detail, a.link AS link,
               a.startTime AS t_start, a.endTime AS t_end, NULL::DOUBLE AS travTime, NULL::DOUBLE AS distance
        FROM p, UNNEST(activities) AS t(a)
        UNION ALL
        SELECT l.sequence, 'leg', l.mode, l.route.startLink || '->' || l.route.endLink,
               NULL::DOUBLE, NULL::DOUBLE, l.travTime, l.route.distance
        FROM p, UNNEST(legs) AS t(l)
    )
    ORDER BY seq;

-- One person's experienced day as trips (see the experienced_trips view), times formatted.
CREATE OR REPLACE MACRO trip_chain(pid) AS TABLE
    SELECT trip, from_act, hms(depart) AS depart, main_mode,
           to_act, hms(arrive) AS arrive,
           round(trav_s) AS trav_s, round(dist_m) AS dist_m, stages
    FROM experienced_trips WHERE personId = pid::VARCHAR ORDER BY trip;

-- One person's experienced (non-interaction) activities with formatted times/durations.
CREATE OR REPLACE MACRO activity_chain(pid) AS TABLE
    SELECT seq, actType, link,
           hms(startTime) AS start, hms(endTime) AS "end",
           hms(maxDuration) AS maxDur, hms(eff_duration) AS eff_dur
    FROM experienced_activities WHERE personId = pid::VARCHAR ORDER BY seq;

-- Realized main-mode share of TRIPS (not legs): each experienced trip counts once, by its main mode.
CREATE OR REPLACE MACRO experienced_mode_share() AS TABLE
    SELECT main_mode AS mode, count(*) AS trips,
           round(100.0 * count(*) / sum(count(*)) OVER (), 1) AS pct
    FROM experienced_trips GROUP BY main_mode ORDER BY trips DESC;

-- Best-response gap over the plan memory (output_plans: choice set per person).
CREATE OR REPLACE MACRO best_response_gap() AS TABLE
    WITH g AS (
        SELECT personId, max(score) AS best, max(score) FILTER (WHERE selected) AS sel
        FROM output_plans GROUP BY personId
    )
    SELECT count(*) FILTER (WHERE sel >= best - 1e-6) AS selected_is_best,
           count(*) AS persons, round(avg(best - sel), 3) AS avg_gap
    FROM g;

-- Freight sanity: share of truck trips whose MAIN (truck) leg is 0 m -- same-link goods movements
-- the small-scale commercial traffic generation produces (truck leg 0 m, start = end link). routingMode
-- still classifies them as truck (the distance heuristic mislabels them walk), but they pull freight VKT
-- down. main_dist_m is the truck leg alone, excluding any access/egress walk.
CREATE OR REPLACE MACRO freight_zero_distance() AS TABLE
    SELECT main_mode, count(*) AS trips,
           count(*) FILTER (WHERE coalesce(main_dist_m, 0) = 0) AS zero_main_leg,
           round(100.0 * count(*) FILTER (WHERE coalesce(main_dist_m, 0) = 0) / count(*), 1) AS pct_zero
    FROM experienced_trips WHERE main_mode LIKE 'truck%'
    GROUP BY main_mode ORDER BY main_mode;

-- Zero-duration activities (start == end, a genuine 0-second stay -- not undefined) by type,
-- with the number of distinct persons affected. A recurring data-quality check.
CREATE OR REPLACE MACRO zero_duration_activities() AS TABLE
    SELECT actType, count(*) AS zero_dur_acts, count(DISTINCT personId) AS persons
    FROM experienced_activities WHERE eff_duration = 0
    GROUP BY actType ORDER BY zero_dur_acts DESC;
