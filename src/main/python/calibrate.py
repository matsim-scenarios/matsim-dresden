#!/usr/bin/env python
# -*- coding: utf-8 -*-

import geopandas as gpd
import os
import pandas as pd

from matsim.calibration import create_calibration, ASCCalibrator, utils, analysis

# %%

# NOTE: script was copied from the matsim-dreden v1.0 calibration on TUB math-cluster.
# The paths in this script correspond to the folder structure there.
# Please adapt the input paths if you want to start your own calibration or talk to VSP. -sm1025

if os.path.exists("mid.csv"):
    srv = pd.read_csv("mid.csv")
    sim = pd.read_csv("sim.csv")

    _, adj = analysis.calc_adjusted_mode_share(sim, srv)

    print(srv.groupby("mode").sum())

    print("Adjusted")
    print(adj.groupby("mode").sum())

    adj.to_csv("mid_adj.csv", index=False)

# %%

modes = ["walk", "car", "ride", "pt", "bike"]
fixed_mode = "walk"
# initial values from calibrated oberlausitz-dresden v2025.1
initial = {
    "bike": -2.04,
    "pt": -1.55,
    "car": 0.38,
    "ride": -0.51
}

# Based on srv2018 for Dresden
target = {
    "walk": 0.26194635,
    "bike": 0.18663860,
    "pt": 0.19017096,
    "car": 0.28107023,
    "ride": 0.08017387
}

region = gpd.read_file("../input/v1.0/vvo_tarifzone_10_dresden/v1.0_vvo_tarifzone_10_dresden_utm32n.shp").to_crs("EPSG:25832")


def filter_persons(persons):
    persons = gpd.GeoDataFrame(persons, geometry=gpd.points_from_xy(persons.home_x, persons.home_y))
    df = gpd.sjoin(persons.set_crs("EPSG:25832"), region, how="inner", predicate="intersects")

    print("Filtered %s persons" % len(df))

    return df


def filter_modes(df):
    # Set multi-modal trips to pt
    df.loc[df.main_mode.str.startswith("pt_"), "main_mode"] = "pt"

    return df[df.main_mode.isin(modes)]


study, obj = create_calibration(
    "calib",
    ASCCalibrator(modes, initial, target, lr=utils.linear_scheduler(start=0.3, interval=12)),
    "/net/ils/matsim-dresden/calibration-10pct-v1.0-fixed-subtours/matsim-dresden-1.0-f9eab41.jar",
    "../input/v1.0/dresden-v1.0-10pct.config.xml",
    args="--10pct --config:plans.inputPlansFile /net/ils/matsim-dresden/calibration-10pct-v1.0-fixed-subtours/dresden-v1.0-10pct.plans-initial.xml.gz",
    jvm_args="-Xmx60G -Xmx60G -XX:+AlwaysPreTouch -XX:+UseParallelGC",
    transform_persons=filter_persons, transform_trips=filter_modes,
    chain_runs=utils.default_chain_scheduler,
    debug=False
)

# %%

study.optimize(obj, 12)
