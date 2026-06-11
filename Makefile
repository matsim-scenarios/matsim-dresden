
N := dresden
V := v1.1
CRS := EPSG:25832

ifndef SUMO_HOME
	export SUMO_HOME := $(abspath ../../Sumo/)
endif

#define some important paths
# osmosis path needs to be in " because of blank space in path...
osmosis := "C:/Program Files/osmosis-0.49.2//bin/osmosis.bat"
germany := $(CURDIR)/../../shared-svn/raw/europe/de/de
germanWideFreight := input/germanWideFreight
shared := $(CURDIR)/../../shared-svn/matsim/scenarios/countries/de/dresden/dresden-v1.1/
sharedOberlausitzDresden := $(CURDIR)/../../shared-svn/projects/matsim-oberlausitz-dresden
dresdenRaw := $(CURDIR)/../../shared-svn/raw/europe/de/dresden

MEMORY ?= 50G
JAR := matsim-$(N)-*.jar

# Scenario creation tool
sc := java -Xms$(MEMORY) -Xmx$(MEMORY) -jar $(JAR)

.PHONY: prepare

# MATSim is built from source via the matsim/ git submodule (pinned commit) and
# installed into the local maven repo (~/.m2), instead of pulling an ephemeral
# PR-labelled release from repo.matsim.org. On a fresh checkout run once:
#   git submodule update --init
# The stamp is regenerated whenever the submodule is moved to a different commit
# (.git/modules/matsim/HEAD changes on checkout/update), which re-triggers $(JAR).
.matsim-install-stamp: .git/modules/matsim/HEAD
	cd matsim && mvn install -DskipTests
#	faster alternative, builds only what dresden needs + their deps:
#	cd matsim && mvn install -N -DskipTests && mvn install -DskipTests -pl :matsim,:application,:simwrapper,:small-scale-traffic-generation,:vsp -am
	touch $@

$(JAR): .matsim-install-stamp
	mvn package -DskipTests

######################################### network creation ############################################################################################

# !! There are important commands in the following, which need to be run to get started.  However, it seems that window systems
# quite often run them also in situations where this should not be needed, and so we comment them out to avoid that. !!

# Required files
#this step is only necessary once. The downloaded network is uploaded to shared-svn/projects/matsim-germany/maps
#when not wanting to download osm data for the current year (2026), there is only one dataset per year available.
#We are sticking to 2024 as we want to depict the status (shortly) before Carola bridge collapsed (sep24).
#we need to manually svn copy the above to shared-svn/raw/europe/de/de/osm because we do not want to state our svn credentials here
input/before-calibration/output/germany-240101.osm.pbf:
	curl https://download.geofabrik.de/europe/germany-240101.osm.pbf \
	-o $@

#retrieve detailed network (see param highway) from OSM
# the .poly files contain point coords. The coordinates should be in EPSG:4326.
#it is rather painful to create them. My workflow is the following:
# 1) create points layer in QGIS with points depicting your boundary area.
# 2) it is important that the points are ordered, so add an id column and number them in increasing order as you go around your area and create the points.
# 3) ad x/y coords as feature attributes: Vector - Geometry Tools - Add Geometry Attributes.
# 4) Export as csv and copy content of csv without the id column to a .poly file.
# see https://wiki.openstreetmap.org/wiki/Osmosis/Polygon_Filter_File_Format for .poly structure
input/before-calibration/output/network-detailed-regional.osm.pbf: input/before-calibration/output/germany-240101.osm.pbf
	$(osmosis) --rb file=$<\
	 --tf accept-ways bicycle=yes highway=motorway,motorway_link,trunk,trunk_link,primary,primary_link,secondary_link,secondary,tertiary,motorway_junction,residential,unclassified,living_street\
	 --bounding-polygon file="input/before-calibration/shp-for-regional-trains_points.poly"\
	 --used-node\
	 --wb $@\

input/before-calibration/output/network-coarse-germany.osm.pbf: input/before-calibration/output/germany-240101.osm.pbf
	$(osmosis) --rb file=$<\
 	 --tf accept-ways highway=motorway,motorway_link,motorway_junction,trunk,trunk_link,primary,primary_link\
 	 --used-node\
 	 --wb $@

input/before-calibration/output/network.osm: input/before-calibration/output/network-coarse-germany.osm.pbf input/before-calibration/output/network-detailed-regional.osm.pbf
	$(osmosis) --rb file=$< --rb file=$(word 2,$^)\
  	 --merge\
  	 --tag-transform file=input/remove-railway.xml\
  	 --wx $@

# !! See comment above on commented-out material.  !!

#	roadTypes are taken either from the general file "osmNetconvert.typ.xml"
#	or from the german one "osmNetconvertUrbanDe.ty.xml"
$(shared)/before-calibration/output/sumo.net.xml: $(shared)/before-calibration/output/network.osm
	"$(SUMO_HOME)/bin/netconvert" --geometry.remove --ramps.guess --ramps.no-split\
	 --type-files "$(SUMO_HOME)/data/typemap/osmNetconvert.typ.xml","$(SUMO_HOME)/data/typemap/osmNetconvertUrbanDe.typ.xml"\
	 --tls.guess-signals true --tls.discard-simple --tls.join --tls.default-type actuated\
	 --junctions.join --junctions.corner-detail 5\
	 --roundabouts.guess --remove-edges.isolated\
	 --no-internal-links --keep-edges.by-vclass passenger,bicycle\
	 --remove-edges.by-vclass hov,tram,rail,rail_urban,rail_fast,pedestrian\
	 --output.original-names --output.street-names\
	 --proj "+proj=utm +zone=32 +ellps=GRS80 +towgs84=0,0,0,0,0,0,0 +units=m +no_defs"\
	 --osm-files $< -o=$@

# transform sumo network to matsim network and clean it afterwards
# free-speed-factor 0.7 (standard is 0.9): see VSP WP 24-08 Figure 2. Dresden is most similar to metropolitan.
#--remove-turn-restrictions used instead of new TurnRestrictionCleaner,
# the cleaner needs more testing, as it destroys the bike network e.g.
#if you change the underlying osm file, you also have to check/change the augustus bridge links in PrepareNetwork.fixAugustusBridgeAllowedModes
$(shared)/before-calibration/output/dresden-v1.1-network.xml.gz: $(shared)/before-calibration/output/sumo.net.xml
	$(sc) prepare network-from-sumo $< --output $@ --free-speed-factor 0.7 --turn-restrictions IGNORE_TURN_RESTRICTIONS
	$(sc) prepare clean-network $@ --output $@ --modes car --modes bike --modes ride --remove-turn-restrictions
#	delete truck as allowed mode (not used), add longDistanceFreight as allowed mode, prepare network for emissions analysis
	$(sc) prepare network\
	 --network $@\
	 --output $@

# gtfs data from 20230113 used because it has way more pt lines in lausitz area than more recent one in shared-svn/matsim-oberlausitz-dresden
# this might not be relevant anymore for this specific model (dresden only), but out of convenience it wont be altered. -sm0925
#--merge-stops mergeToParentAndRouteTypes merges all tracks at a station to one stop according to GL. This avoids agents waiting at track 1 while there is a suitable connection at track 2 e.g.
#the following date is a wednesday
input/before-calibration/output/dresden-v1.1-network-with-pt.xml.gz: input/before-calibration/output/dresden-v1.1-network.xml.gz
	$(sc) prepare transit-from-gtfs --network $<\
	 --output="input/before-calibration/output/"\
	 --name $N-$V --date "2023-01-11" --target-crs $(CRS) \
	 input/gtfs/20230113_regio.zip\
	 input/gtfs/20230113_train_short.zip\
	 input/gtfs/20230113_train_long.zip\
	 --prefix regio_,short_,long_\
	 --shp input/before-calibration/shp-for-regional-trains-utm32n.shp\
	 --shp input/before-calibration/shp-for-regional-trains-utm32n.shp\
	 --shp input/shp/germany-area.shp\
	 --merge-stops mergeToParentAndRouteTypes

# create matsim counts file
# count to link assignments have been checked manually, they look correct. With exception of the following stations:
#Simmersdorf, Schlagsdorf-Grenze, Plessa, OU Radegast. The assigned links have to be switched for the 4 stations.
#this has to be done via a manual assignment in a csv file provided via --counts-mapping. The file is in shared-svn.
input/before-calibration/output/dresden-v1.1-counts-bast.xml.gz: ../../shared-svn/matsim/scenarios/countries/de/dresden/dresden-v1.1/before-calibration/output/$N-$V-network-with-pt.xml.gz ../../shared-svn/matsim/scenarios/countries/de/dresden/dresden-v1.1/before-calibration/matsim-dresden-v1.1-manual-count-link-assignment.csv
	$(sc) prepare counts-from-bast\
		--network $<\
		--motorway-data $(germany)/bast/2023_A_S.zip\
		--primary-data $(germany)/bast/2023_B_S.zip\
		--station-data $(germany)/bast/Jawe2023.csv\
		--year 2023\
		--shp input/before-calibration/shp-for-regional-trains-utm32n.shp --shp-crs $(CRS)\
		--output $@\
		--counts-mapping $(word 2,$^)

########################### population creation ######################################################################################

# extract dresden long haul freight traffic trips from german wide file
input/before-calibration/output/plans-longHaulFreight.xml.gz: $(germanWideFreight)/german_freight.100pct.plans.xml.gz $(germanWideFreight)/germany-europe-network.xml.gz
	$(sc) prepare extract-freight-trips $<\
	 --network $(word 2,$^)\
	 --input-crs $(CRS)\
	 --target-crs $(CRS)\
	 --shp input/before-calibration/shp-for-regional-trains-utm32n.shp\
	 --shp-crs $(CRS)\
	 --cut-on-boundary\
	 --legMode "truck40t"\
	 --subpopulation "longDistanceFreight"\
	 --output $@

# create facilities for commercial traffic
# the following 2 steps are typically run on the math cluster by Ricardo Ewert. the steps are here for documentation.
# the necessary small scale commercial traffic plans file is copied from the cluster into the local directory for further use.
# on the cluster, the plans are located at:
#input/v1.0/commercialFacilities.xml.gz:
#	$(sc) prepare create-data-distribution-of-structure-data\
#	 --outputFacilityFile $@\
#	 --outputDataDistributionFile $(sharedOberlausitzDresden)/data/commercial_traffic/output/dataDistributionPerZone.csv\
#	 --landuseConfiguration useOSMBuildingsAndLanduse\
# 	 --regionsShapeFileName $(sharedOberlausitzDresden)/data/commercial_traffic/input/oberlausitz_dresden_regions_25832.shp\
#	 --regionsShapeRegionColumn "gen"\
#	 --zoneShapeFileName $(sharedOberlausitzDresden)/data/commercial_traffic/input/oberlausitz_dresden_zones_25832.shp\
#	 --zoneShapeFileNameColumn "zone"\
#	 --buildingsShapeFileName $(sharedOberlausitzDresden)/data/commercial_traffic/input/oberlausitz_dresden_buildings_25832.shp\
#	 --shapeFileBuildingTypeColumn "building"\
#	 --landuseShapeFileName $(sharedOberlausitzDresden)/data/commercial_traffic/input/oberlausitz_dresden_landuse_25832.shp\
#	 --shapeFileLanduseTypeColumn "landuse"\
#	 --shapeCRS "EPSG:25832"\
#	 --pathToInvestigationAreaData $(sharedOberlausitzDresden)/data/commercial_traffic/input/investigationAreaData.csv

# generate small scale commercial traffic
#input/v1.0/oberlausitz-dresden-small-scale-commercialTraffic-v1.0-100pct.xml.gz: input/$V/$N-$V-network.xml.gz input/$V/commercialFacilities.xml.gz
#	$(sc) prepare generate-small-scale-commercial-traffic\
#	  input/$V/$N-$V-100pct.config.xml\
#	 --pathToDataDistributionToZones $(sharedOberlausitzDresden)/data/commercial_traffic/output/dataDistributionPerZone.csv\
#	 --pathToCommercialFacilities $(word 2,$^)\
#	 --sample 1.0\
#	 --jspritIterations 100\
#	 --additionalTravelBufferPerIterationInMinutes 60\
#	 --creationOption "createNewCarrierFile"\
#	 --network $<\
#	 --smallScaleCommercialTrafficType "completeSmallScaleCommercialTraffic"\
#	 --zoneShapeFileName $(sharedOberlausitzDresden)/data/commercial_traffic/input/oberlausitz_dresden_zones_25832.shp\
#	 --zoneShapeFileNameColumn "zone"\
#	 --shapeCRS "EPSG:25832"\
#	 --resistanceFactor_commercialPersonTraffic 0.2\
#	 --resistanceFactor_goodsTraffic 0.1\
#	 --numberOfPlanVariantsPerAgent 5\
#	 --nameOutputPopulation $@\
#	 --pathOutput output/commercialPersonTraffic
#
#	mv output/commercialPersonTraffic/$@ $@

# trajectory-to-plans formerly was a collection of methods to prepare a given population
# now, most of the functions of this class do have their own class (downsample, splitduration types...)
# it basically only transforms the old attribute format to the new one
# --max-typical-duration set to 0 because this switches off the duration split, which we do later
input/before-calibration/output/prepare-100pct.plans.xml.gz: input/20250123_Teilmodell_Hoyerswerda/Modell/population.xml.gz input/20250130_Teilmodell_Hoyerswerda/Modell/personAttributes.xml.gz input/before-calibration/output/$N-$V-network.xml.gz
	$(sc) prepare trajectory-to-plans\
	 --name prepare --sample-size 1 --output input/before-calibration/output\
	 --max-typical-duration 0\
	 --population $<\
	 --attributes $(word 2,$^)
# adapt coords of activities in the wider network such that they are closer to a link
# such that agents do not have to walk as far as before
	$(sc) prepare adjust-activity-to-link-distances $@\
 	  --shp input/before-calibration/shp-for-regional-trains-utm32n.shp --shp-crs $(CRS)\
 	  --scale 1.15\
 	  --input-crs $(CRS)\
 	  --network $(word 3,$^)\
 	  --output input/before-calibration/output/prepare-100pct.plans-adj.xml.gz
# resolve senozon aggregated grid coords (activities): distribute them based on landuse.shp
	$(sc) prepare resolve-grid-coords input/before-calibration/output/prepare-100pct.plans-adj.xml.gz\
	 --input-crs $(CRS)\
	 --grid-resolution 300\
	 --landuse input/landuse/landuse.shp\
	 --output $@

# the population from snz was delivered for oberlausitz-dresden, so we have to cut out the dresden population.
#this uses the scenario cutout class from CR, which is able to produce a cutout network, network change events and facilities.
#here, we just want to use it to cut out the population. Everything else is not used. --output-network is a required option, so we delete the network afterwards.
input/before-calibration/output/prepare-cutout-100pct.plans.xml.gz: input/before-calibration/output/prepare-100pct.plans.xml.gz input/before-calibration/output/$N-$V-network.xml.gz
	$(sc) prepare scenario-cutout\
	 --population $<\
	 --network $(word 2,$^)\
	 --output-population $@\
	 --output-network input/before-calibration/output/$N-$V-network-cutout-to-be-deleted.xml.gz\
	 --input-crs $(CRS)\
	 --shp input/shp/v1.1_vvo_tarifzone_10_dresden_utm32n.shp\
	 --shp-crs $(CRS)\
	 --buffer 10000\
	 --check-beeline

# same goes for small scale commercial traffic.
input/before-calibration/output/dresden-small-scale-commercialTraffic-v1.1-100pct.xml.gz: input/before-calibration/output/oberlausitz-dresden-small-scale-commercialTraffic-v1.0-100pct.xml.gz input/before-calibration/output/$N-$V-network.xml.gz
	 $(sc) prepare scenario-cutout\
	 --population $<\
	 --network $(word 2,$^)\
	 --output-population $@\
	 --output-network input/before-calibration/output/$N-$V-commercial-network-cutout-to-be-deleted.xml.gz\
	 --input-crs $(CRS)\
	 --shp input/shp/v1.1_vvo_tarifzone_10_dresden_utm32n.shp\
	 --shp-crs $(CRS)\
	 --buffer 10000\
	 --check-beeline

input/before-calibration/output/prepare-cutout-fixed-subtours-100pct.plans.xml.gz: input/before-calibration/output/prepare-cutout-100pct.plans.xml.gz
# change modes in subtours with chain based AND non-chain based by choosing mode for subtour randomly
	$(sc) prepare fix-subtour-modes --coord-dist 100 --input $< --output $@
# set car availability for agents below 18 to false, standardize some person attrs, set home coords, set person income
	$(sc) prepare population $@ --output $@

# this step is necessary to process the plans for a 0it test. the 0it test is used to generate trips and persons tables
# for the calculation of a number of short distance trips to add (compared to reference data).
# the calculation is done in python script extract_ref_data.py
#some plans apparently have a sum of act_duration >> 86400. This is some issue in the input data, we decided to ignore that for dresden v1.1.
#To fix the above issue, we set --overlong-plans-factor 1.5
# $(shared)/before-calibration/output/prepare-100pct-with-trips-split-merged.plans_FOR_0IT_TEST.xml.gz: input/before-calibration/output/prepare-cutout-fixed-subtours-100pct.plans.xml.gz
# 	$(sc) prepare split-activity-types-duration\
# 		--input $<\
# 		--overlong-plans-factor 1.5\
# 		--exclude commercial_start,commercial_end,freight_start,freight_end,service\
# 		--output $@

# generate some short distance trips, which in senozon data generally are missing
# 1) we have to calculate the number of trips to add with python script create_ref.py
# for that it might be necessary to run split-activity-types-duration (see below) separately.
# 2) trip range 700m because:
# when adding 1km trips (default value), too many trips of bin 1km-2km were also added.
# the range value is beeline, so the trip distance (routed) often is higher than 1km
# 3) for dresden we have SrV data. currently using 2018 data.
# 43524 additional short trips seems to few here. Usually we are around 250k..
# I checked the script (extract_ref_data.py) which calculates --num-trips and it seems to be correct. Continuing with 43.5k trips here.
input/before-calibration/output/prepare-100pct-short-trips.plans.xml.gz: input/before-calibration/output/prepare-cutout-fixed-subtours-100pct.plans.xml.gz
	$(sc) prepare generate-short-distance-trips\
		--population $<\
		--input-crs $(CRS)\
		--shp input/shp/v1.1_vvo_tarifzone_10_dresden_utm32n.shp --shp-crs $(CRS)\
		--range 700\
		--num-trips 43524\
		--output $@

#   this step *has to* be done after the generation of short distance trips.
#	split activity types to type_duration for the scoring to take into account the typical duration
#some plans apparently have a sum of act_duration >> 86400. This is some issue in the input data, we decided to ignore that for dresden v1.1.
#To fix the above issue, we set --overlong-plans-factor 1.5
#	TODO: usage of --end-time-to-duration does not remove all end times of activities below 1800s (default value)
input/before-calibration/output/prepare-100pct-split.plans.xml.gz: input/before-calibration/output/prepare-100pct-short-trips.plans.xml.gz
	$(sc) prepare split-activity-types-duration\
		--input $<\
		--max-typical-duration 97200\
		--overlong-plans-factor 1.5\
		--exclude commercial_start,commercial_end,freight_start,freight_end,service\
		--output $@

#	merge person and freight pops
input/before-calibration/output/prepare-100pct-with-trips-split-merged.plans.xml.gz: input/before-calibration/output/prepare-100pct-split.plans.xml.gz input/before-calibration/output/plans-longHaulFreight.xml.gz input/before-calibration/output/dresden-small-scale-commercialTraffic-v1.1-100pct.xml.gz
	$(sc) prepare merge-populations $< $(word 2,$^) $(word 3,$^) --output $@

# Step 1: assign activity facilities (one facility per activity coord).
# there should be more detailed algorithms to create activity facilities than the below class.
# see https://github.com/matsim-scenarios/matsim-hannover/issues/1
# This single command produces two artifacts: the population (with facility refs) and the
# facilities file. The facilities file is declared as its own target below so the rest of
# the build can depend on it explicitly.
input/before-calibration/output/$N-$V-100pct.plans-with-facilities.xml.gz: input/before-calibration/output/prepare-100pct-with-trips-split-merged.plans.xml.gz input/before-calibration/output/$N-$V-network-with-pt.xml.gz
	$(sc) prepare facilities\
			--input-population $<\
			--network $(word 2,$^)\
			--output-population $@\
			--output-facilities input/before-calibration/output/$N-$V-activity-facilities.xml.gz

# The facilities file is co-produced by the step above. Declaring it as a target that depends
# on (but does not rebuild) the population makes it a first-class node in the dependency graph
# without relying on GNU Make 4.3 grouped targets (this repo's make is 3.81).
input/before-calibration/output/$N-$V-activity-facilities.xml.gz: input/before-calibration/output/$N-$V-100pct.plans-with-facilities.xml.gz

# Step 2: remove wrongly-named commercial vehicle types.
# for small scale commercial traffic generation some vehicle types (truck8t, truck18t and truck40t) are named differently than in this scenario.
# this causes a crash of simulation. We delete them here and they will be auto generated when starting the sim. For car the veh types are named equally.
input/before-calibration/output/$N-$V-100pct.plans-veh-removed.xml.gz: input/before-calibration/output/$N-$V-100pct.plans-with-facilities.xml.gz
	$(sc) prepare remove-vehicles $< --output $@ --skip car

# Step 3: fix subtours again after assignment of facilities to activities -> final initial plans.
input/before-calibration/output/$N-$V-100pct.plans-initial.xml.gz: input/before-calibration/output/$N-$V-100pct.plans-veh-removed.xml.gz
	$(sc) prepare fix-subtour-modes --coord-dist 100 --input $< --output $@

# Step 4: down-sampled populations, derived from the finished 100pct plans.
# A single downsample-population call writes several files at once
# (dresden-v1.1-25pct/10pct/1pct/...-plans-initial.xml.gz). We track the group with a stamp
# file rather than naming each output as a target: the tool builds output names by rounding
# the share to whole percent (e.g. 0.001 -> "0pct"), which makes the individual filenames
# awkward to predict. The stamp depends on the 100pct plans, so the samples are rebuilt only
# when the source changes -- not as a side effect of building the 100pct artifact.
input/before-calibration/output/$N-$V-downsampled.stamp: input/before-calibration/output/$N-$V-100pct.plans-initial.xml.gz
	$(sc) prepare downsample-population $<\
		--sample-size 1\
		--samples 0.25 0.1 0.01 0.001
	touch $@

.PHONY: downsample
downsample: input/before-calibration/output/$N-$V-downsampled.stamp

# output of check population seems to be ok. -sm0426
check: input/before-calibration/output/$N-$V-100pct.plans-initial.xml.gz
	$(sc) analysis check-population $<\
 	 --input-crs $(CRS)\
	 --shp input/shp/v1.1_vvo_tarifzone_10_dresden_utm32n.shp --shp-crs $(CRS)

# Aggregated target
prepare: input/before-calibration/output/$N-$V-100pct.plans-initial.xml.gz input/before-calibration/output/$N-$V-activity-facilities.xml.gz input/before-calibration/output/$N-$V-downsampled.stamp input/before-calibration/output/$N-$V-network-with-pt.xml.gz
	echo "Done"