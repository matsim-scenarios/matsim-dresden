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

-- The plan's elements, one row each, in plan order. A plan row carries ONE ordered elements[]
-- array (activities and legs interleaved, as in the XML), so unrolling it is a single UNNEST --
-- no UNION ALL, no null-padding to make two branches line up. The array position is the plan
-- element index, recovered with generate_subscripts(); the parquet has no sequence column.
-- e.kind is 'activity' or 'leg'; the fields of the other kind are NULL.
-- NB seq is 1-BASED (it is the array subscript, so elements[seq] is this element). The old
-- activities[]/legs[] schema had a 0-based `sequence` column, so seq values here are the old
-- ones + 1; everything else these views produce is unchanged.
CREATE OR REPLACE VIEW plan_elements AS
    SELECT personId, planIdx, selected, score, personAttributes,
           generate_subscripts(elements, 1) AS seq,
           unnest(elements) AS e
    FROM experienced_plans;

-- The "person table" the plan-grain schema doesn't have: one row per person with the
-- generic person attributes (subpopulation, carAvail, income, SNZ_*, ...). Person attributes
-- are rolled out onto every plan row, so this just dedups them back to one row per person.
CREATE OR REPLACE VIEW persons AS
    SELECT personId, any_value(personAttributes) AS attributes
    FROM experienced_plans GROUP BY personId;

-- ---- derived view: experienced plans decomposed into trips -----------------
-- One row per trip per person. A "trip" is the stage chain (access/egress walk +
-- interaction activities + main leg) between two "real" (non-interaction) activities,
-- collapsed to one row. main_mode = the leg routingMode (MATSim's authoritative trip main
-- mode, identical across a trip's stage legs), falling back to the longest-distance stage's
-- mode if routingMode is absent. trav_s / dist_m accumulate over ALL stage legs. Times are
-- raw seconds -- wrap with hms() for HH:MM:SS display (see trip_chain).
CREATE OR REPLACE VIEW experienced_trips AS
    WITH ranked AS (   -- real_rank = # real (non-interaction) activities seen so far within the person
        SELECT *, sum(CASE WHEN e.kind = 'activity' AND e.actType NOT LIKE '%interaction%' THEN 1 ELSE 0 END)
                      OVER (PARTITION BY personId ORDER BY seq) AS real_rank
        FROM plan_elements
    ),
    real_acts AS (   -- the trip boundaries (rank r within person)
        SELECT personId, real_rank AS r, e.actType, e.startTime AS t_start, e.endTime AS t_end
        FROM ranked WHERE e.kind = 'activity' AND e.actType NOT LIKE '%interaction%'
    ),
    trip_legs AS (   -- stage legs grouped into their trip (a leg's real_rank = its trip id)
        SELECT personId, real_rank AS trip_id,
               coalesce(max(e.routingMode), arg_max(e.mode, coalesce(e.route.distance, 0))) AS main_mode,
               sum(e.travTime) AS trav_s, sum(e.route.distance) AS dist_m,
               -- distance of the main-mode leg(s) only (mode = routingMode), i.e. excluding access/egress walk
               sum(e.route.distance) FILTER (WHERE e.mode = e.routingMode) AS main_dist_m,
               list(e.mode ORDER BY seq) AS stages
        FROM ranked WHERE e.kind = 'leg' GROUP BY personId, real_rank
    )
    SELECT a.personId, a.r AS trip,
           a.actType AS from_act, a.t_end AS depart,
           tl.main_mode, b.actType AS to_act, b.t_start AS arrive,
           tl.trav_s, tl.dist_m, tl.main_dist_m, tl.stages,
           pp.attributes AS personAttributes   -- filter inline, e.g. personAttributes['subpopulation']
    FROM real_acts a
    JOIN real_acts b ON b.personId = a.personId AND b.r = a.r + 1
    LEFT JOIN trip_legs tl ON tl.personId = a.personId AND tl.trip_id = a.r
    LEFT JOIN persons pp ON pp.personId = a.personId
    ORDER BY a.personId, a.r;   -- naturally sorted, like the plans (personId, then trip)

-- ---- derived view: experienced real activities (stage/interaction activities dropped) -------
-- One row per non-interaction activity per person. These are EXPERIENCED activities, so there is
-- no maxDuration column (that is an intended-plan field, kept in the parquet for plans that could
-- be either). eff_duration = endTime - startTime, which is NULL exactly when a start or an end is
-- missing -- i.e. the day's first (no start) and last (no end) activity. That NULL is distinct from
-- a genuine 0.0 (start == end): filter eff_duration = 0.0 for real zero-duration stays, and
-- eff_duration IS NULL for the morning/evening ends of the day. No wrap-around / interpolation is
-- modelled -- activities stay exactly as the experienced plan has them. Times/durations are raw
-- seconds (format with hms()); personAttributes lets you filter by e.g. subpopulation without a join.
CREATE OR REPLACE VIEW experienced_activities AS
    SELECT personId, seq, e.actType, e.link,
           e.startTime, e.endTime,
           e.endTime - e.startTime AS eff_duration,   -- NULL iff start or end missing (day's first/last)
           personAttributes
    FROM plan_elements
    WHERE e.kind = 'activity' AND e.actType NOT LIKE '%interaction%'
    ORDER BY personId, seq;   -- naturally sorted, like the plans (personId, then sequence)

-- ---- helpers ---------------------------------------------------------------

-- Seconds -> a native INTERVAL that prints as HH:MM:SS. There is no >24h TIME type in DuckDB
-- (TIME is capped at 24:00:00), but INTERVAL keeps MATSim's after-midnight convention (24:56:49,
-- 27:00:00) and is arithmetic-friendly. NB: cast to BIGINT, not the earlier printf -- a CAST to
-- INTEGER in DuckDB ROUNDS to nearest, which had corrupted the hand-formatted fields.
CREATE OR REPLACE MACRO hms(t) AS to_seconds(t::BIGINT);

-- ---- macros: saved queries -------------------------------------------------

-- Full experienced day of one person: activities and legs interleaved by sequence.
CREATE OR REPLACE MACRO plan_chain(pid) AS TABLE
    SELECT seq, e.kind,
           coalesce(e.actType, e.mode) AS detail,
           coalesce(e.link, e.route.startLink || '->' || e.route.endLink) AS link,
           hms(e.startTime) AS start, hms(e.endTime) AS "end",
           round(e.travTime) AS trav_s, round(e.route.distance) AS dist_m
    FROM plan_elements WHERE personId = pid::VARCHAR
    ORDER BY seq;

-- One person's experienced day as trips (see the experienced_trips view), times formatted.
CREATE OR REPLACE MACRO trip_chain(pid) AS TABLE
    SELECT trip, from_act, hms(depart) AS depart, main_mode,
           to_act, hms(arrive) AS arrive,
           round(trav_s) AS trav_s, round(dist_m) AS dist_m, stages
    FROM experienced_trips WHERE personId = pid::VARCHAR ORDER BY trip;

-- One person's experienced (non-interaction) activities with formatted times/durations.
-- The day's first/last activity have a NULL eff_dur (no start / no end).
CREATE OR REPLACE MACRO activity_chain(pid) AS TABLE
    SELECT seq, actType, link,
           hms(startTime) AS start, hms(endTime) AS "end", hms(eff_duration) AS eff_dur
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
