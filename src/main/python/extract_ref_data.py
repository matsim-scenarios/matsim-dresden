#!/usr/bin/env python
# -*- coding: utf-8 -*-

import geopandas as gpd
import numpy as np
import os
import pandas as pd
from matsim.scenariogen.data import EconomicStatus, TripMode, preparation, run_create_ref_data


# IMPORTANT NOTE:
# as of now (2025-10-09) we cannot use the released version of matsim python tools.
# it does not have dreden as a region for regiostar classification and therefore crashes.
# instead, we can use 1) the local version of matsim python tools (if checked out) with pip install -e [path-to-tools-dir]
# or 2) use the specific branch on github with the necessary changes: pip install git+https://github.com/matsim-vsp/matsim-python-tools.git@add_dresden_region


CRS = "EPSG:25832"

def person_filter(df):
    """ Default person filter for reference data. """
    # only monday to thursday. mb this should be tue-thu?!
    df = df[df.reporting_day <= 4]
    # filter for dresden. This should include all the data anyways
    df = df[df.location == "Dresden"]

    # assigns age groups to persons.
    df["age"] = preparation.cut(df.age, [0, 12, 18, 25, 35, 66, np.inf])

    # this splits zone ids by "-". 1-2-3. The first always is the Oberbezirk". We only want to use the Oberbezirk.
    df["zone"] = df.zone.str.split("-", expand=True)[0]

    # fill missing income values with sample from existing income values
    preparation.fill(df, "income", -1)
    # also add economic status for persons with added income from step before.
    preparation.compute_economic_status(df)

    # assign income group based on weighted hh size.
    # see https://stat.fi/meta/kas/ekvivalentti_tu_en.html which is used to calc df.equivalent_size
    df["income"] = preparation.cut(df.income / df.equivalent_size, [0, 250, 500, 750, 1000, 1250, 1500, 1750, 2000, 2500, 3000, 3500, np.inf])

    return df


def trip_filter(df):
    # Motorcycles are counted as cars
    # TODO: check if other modes should be counted as car, too (for srv2023)
    df.loc[df.main_mode == TripMode.MOTORCYCLE, "main_mode"] = TripMode.CAR

    # Other mode are ignored in the total share
    # TODO: check if other still in data (for 2023)
    df = df[df.main_mode != TripMode.OTHER]

    return df


if __name__ == "__main__":
    # use srv 2018 data for now. There is no infrastructure for srv2023 yet in matsim python tools. TODO
    d = os.path.expanduser("../../../../../shared-svn/projects/agimo/data/dresden-model/SrV/2018")

    # TODO: prüfen ob in run_create_ref_data.create auch geprüft wird, ob der Weg überhaupt gültig ist
    #  SrV column E_WEG_GUELTIG: Gültiger Weg (Angaben zu Dauer und Länge vorhanden, Länge <100km)
    result = run_create_ref_data.create(
        d,
        person_filter, trip_filter,
        run_create_ref_data.InvalidHandling.REMOVE_TRIPS,
        ref_groups=["age", "income", "economic_status", "employment", "car_avail", "bike_avail", "pt_abo_avail", "zone"]
    )

    print(result.share)

    print(result.groups)

    t = result.trips

    # Weighted mean
    wm = lambda x: np.average(x, weights=t.loc[x.index, "t_weight"])

    t["speed"] = (t.gis_length * 3600) / (t.duration * 60)

    aggr = t.groupby("main_mode").agg(kmh=("speed", wm), dist=("gis_length", wm))

    print("Avg per mode")
    print(aggr)

    print("Mobile persons", result.persons[result.persons.mobile_on_day == True].p_weight.sum() / result.persons.p_weight.sum())

    aggr = t.groupby(["main_mode", "age"]).agg(kmh=("speed", wm), dist=("gis_length", wm))
    print("Avg per mode and age")
    print(aggr)

    # Calculate the number of short distance trips that are missing in the simulated data
    # This function required that one run with 0 iterations has been performed beforehand

    # this says 10pct, but is 100pct population, number of person: 1134991 (without freight agents)
    # same for trips
    sim_persons = pd.read_csv("Y:/net/ils/matsim-dresden/first-run-0it/output/output-dresden-10pct/dresden-10pct.output_persons.csv.gz",
                              delimiter=";", dtype={"person": "str"})
    sim_persons = sim_persons[sim_persons.subpopulation == "person"]
    sim_persons = gpd.GeoDataFrame(sim_persons,
                                   geometry=gpd.points_from_xy(sim_persons.home_x, sim_persons.home_y)).set_crs(CRS)
    print("Person agents in population: ", len(sim_persons))

    # Defines the study area
    # this is needed because we need to filter the agents with home loc in out study area.
    region = gpd.read_file("../../../../../shared-svn/projects/agimo/data/dresden-model/shp/vvo_tarifzone_10_dresden_utm32n.shp").to_crs(CRS)

    sim_persons = gpd.sjoin(sim_persons, region, how="inner", predicate="intersects")
    print("Filtered residents of shp file: ", len(sim_persons))

    sim = pd.read_csv("Y:/net/ils/matsim-dresden/first-run-0it/output/output-dresden-10pct/dresden-10pct.output_trips.csv.gz",
                      delimiter=";", dtype={"person": "str"})

    sim = pd.merge(sim, sim_persons, how="inner", left_on="person", right_on="person", validate="many_to_one")

    share, add_trips = preparation.calc_needed_short_distance_trips(t, sim, max_dist=700)
    print("Short distance trip missing: ", add_trips)
    print("Target share of trips <= 700m: ", share)