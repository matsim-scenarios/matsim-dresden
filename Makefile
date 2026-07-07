
N := dresden
V := v1.1
CRS := EPSG:25832

ifndef SUMO_HOME
	export SUMO_HOME := $(abspath ../../Sumo/)
endif

#define some important paths
# osmosis path needs to be in " because of blank space in path...
osmosis := "osmosis"
germany := $(CURDIR)/../../shared-svn/raw/europe/de/de
germanWideFreight := input/germanWideFreight
shared := $(CURDIR)/../../shared-svn/matsim/scenarios/countries/de/dresden/dresden-v1.1/
sharedOberlausitzDresden := $(CURDIR)/../../shared-svn/projects/matsim-oberlausitz-dresden
dresdenRaw := $(CURDIR)/../../shared-svn/raw/europe/de/dresden

MEMORY ?= 50G
JAR := matsim-$(N)-*.jar

# Scenario creation tool
sc := java -Xms$(MEMORY) -Xmx$(MEMORY) -jar $(JAR)

# Last iteration for the run-* targets. Override on the command line, e.g. `make run-1pct LAST_IT=1`.
LAST_IT ?= 500

.PHONY: prepare run run-1pct run-0pct

$(JAR):
	./mvnw package -DskipTests

input/before-calibration/output:
	mkdir -p input/before-calibration/output

######################################### network creation ############################################################################################
#retrieve detailed network (see param highway) from OSM
# the .poly files contain point coords. The coordinates should be in EPSG:4326.
#it is rather painful to create them. My workflow is the following:
# 1) create points layer in QGIS with points depicting your boundary area.
# 2) it is important that the points are ordered, so add an id column and number them in increasing order as you go around your area and create the points.
# 3) ad x/y coords as feature attributes: Vector - Geometry Tools - Add Geometry Attributes.
# 4) Export as csv and copy content of csv without the id column to a .poly file.
# see https://wiki.openstreetmap.org/wiki/Osmosis/Polygon_Filter_File_Format for .poly structure
input/before-calibration/output/network-detailed-regional.osm.pbf: input/osm/germany-240101.osm.pbf | input/before-calibration/output
	$(osmosis) --rb file=$<\
	 --tf accept-ways bicycle=yes highway=motorway,motorway_link,trunk,trunk_link,primary,primary_link,secondary_link,secondary,tertiary,motorway_junction,residential,unclassified,living_street\
	 --bounding-polygon file="input/shp/shp-for-regional-trains_points.poly"\
	 --used-node\
	 --wb $@\

input/before-calibration/output/network-coarse-germany.osm.pbf: input/osm/germany-240101.osm.pbf | input/before-calibration/output
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
input/before-calibration/output/sumo.net.xml: input/before-calibration/output/network.osm
	"netconvert" --geometry.remove --ramps.guess --ramps.no-split\
	 --type-files "input/sumo/osmNetconvert.typ.xml","input/sumo/osmNetconvertUrbanDe.typ.xml"\
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
input/before-calibration/output/dresden-v1.1-network.xml.gz: input/before-calibration/output/sumo.net.xml
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
	 --shp input/shp/shp-for-regional-trains-utm32n.shp\
	 --shp input/shp/shp-for-regional-trains-utm32n.shp\
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
		--shp input/shp/shp-for-regional-trains-utm32n.shp --shp-crs $(CRS)\
		--output $@\
		--counts-mapping $(word 2,$^)

########################### population creation ######################################################################################

# extract dresden long haul freight traffic trips from german wide file
input/before-calibration/output/plans-longHaulFreight.xml.gz: $(germanWideFreight)/german_freight.100pct.plans.xml.gz $(germanWideFreight)/germany-europe-network.xml.gz
	$(sc) prepare extract-freight-trips $<\
	 --network $(word 2,$^)\
	 --input-crs $(CRS)\
	 --target-crs $(CRS)\
	 --shp input/shp/shp-for-regional-trains-utm32n.shp\
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
# --max-typical-duration set to 0 because this switches off the duration split, which we don't do anymore
input/before-calibration/output/prepare-100pct.plans.xml.gz: input/20250123_Teilmodell_Hoyerswerda/Modell/population.xml.gz input/20250130_Teilmodell_Hoyerswerda/Modell/personAttributes.xml.gz input/before-calibration/output/$N-$V-network.xml.gz
	$(sc) prepare trajectory-to-plans\
	 --name prepare --sample-size 1 --output input/before-calibration/output\
	 --max-typical-duration 0\
	 --population $<\
	 --attributes $(word 2,$^)
# adapt coords of activities in the wider network such that they are closer to a link
# such that agents do not have to walk as far as before
	$(sc) prepare adjust-activity-to-link-distances $@\
 	  --shp input/shp/shp-for-regional-trains-utm32n.shp --shp-crs $(CRS)\
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
input/before-calibration/output/dresden-small-scale-commercialTraffic-v1.1-100pct.xml.gz: input/smallScaleCommercialTraffic/oberlausitz-dresden-small-scale-commercialTraffic-v1.0-100pct.xml.gz input/before-calibration/output/$N-$V-network.xml.gz
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

# final steps of the person population, before freight traffic is merged in.
input/before-calibration/output/prepare-100pct-persons.xml.gz: input/before-calibration/output/prepare-100pct-short-trips.plans.xml.gz
# switch off wrap-around scoring: split first and last act of the day into separate _morning and _evening act types.
	$(sc) prepare split-wrap-around-activities $< --output $@
# encode each activity's typical duration as a "typicalDuration" attribute on the activity. Must run after the
# wrap-around split, so the (now differing) morning/evening types take the non-wrap-around branch.
# --simulation-period-in-days must match config.scenario().getSimulationPeriodInDays() set in DresdenModel (1.125).
	$(sc) prepare encode-typical-duration $@ --output $@ --simulation-period-in-days 1.125
# for short activities, remove the end time and encode the span as a maximum duration instead.
	$(sc) prepare end-time-to-duration $@ --output $@

#	merge person and freight pops
input/before-calibration/output/prepare-100pct-with-trips-merged.plans.xml.gz: input/before-calibration/output/prepare-100pct-persons.xml.gz input/before-calibration/output/plans-longHaulFreight.xml.gz input/before-calibration/output/dresden-small-scale-commercialTraffic-v1.1-100pct.xml.gz
	$(sc) prepare merge-populations $< $(word 2,$^) $(word 3,$^) --output $@

# there should be more detailed algorithms to create activity facilities than the below class. it creates one facility per activity coord.
# see https://github.com/matsim-scenarios/matsim-hannover/issues/1
input/before-calibration/output/$N-$V-100pct.plans-initial.xml.gz: input/before-calibration/output/prepare-100pct-with-trips-merged.plans.xml.gz input/before-calibration/output/$N-$V-network-with-pt.xml.gz
	$(sc) prepare facilities\
    		--input-population $<\
            --network $(word 2,$^)\
            --output-population $@\
            --output-facilities input/before-calibration/output/$N-$V-activity-facilities.xml.gz
# for small scale commercial traffic generation some vehicle types (truck8t, truck18t and truck40t) are named differently than in this scenario.
# this causes a crash of simulation. We delete them here and they will be auto generated when starting the sim. For car the veh types are named equally.
	$(sc) prepare remove-vehicles\
			$@\
			--output $@\
			--skip car
# we need to fix subtours again after assignment of facilities to activities.
	$(sc) prepare fix-subtour-modes --coord-dist 100 --input $@ --output $@
	$(sc) prepare downsample-population $@\
    	 --sample-size 1\
    	 --samples 0.25 0.1 0.01 0.001\

# output of check population seems to be ok. -sm0426
check: input/before-calibration/output/$N-$V-100pct.plans-initial.xml.gz
	$(sc) analysis check-population $<\
 	 --input-crs $(CRS)\
	 --shp input/shp/v1.1_vvo_tarifzone_10_dresden_utm32n.shp --shp-crs $(CRS)

# Aggregated target
prepare: input/before-calibration/output/$N-$V-100pct.plans-initial.xml.gz input/before-calibration/output/$N-$V-network-with-pt.xml.gz
	echo "Done"

# Run the before-calibration scenario with the initial (uncalibrated) config. Assumes the prepare pipeline has
# produced the inputs referenced by input/prepare-config.yml (run `make prepare` first). The config is set up for
# the 10pct sample; the run-1pct / run-0pct targets below override the sample-dependent parameters.
run: input/prepare-config.yml
	$(sc) --config $<\
	 --config:controller.lastIteration=$(LAST_IT)

# Run at 1pct sample.
run-1pct: input/prepare-config.yml
	$(sc) --config $<\
	 --config:plans.inputPlansFile=before-calibration/output/$N-$V-1pct.plans-initial.xml.gz\
	 --config:qsim.flowCapacityFactor=0.01\
	 --config:qsim.storageCapacityFactor=0.01\
	 --config:counts.countsScaleFactor=0.01\
	 --config:simwrapper.sampleSize=0.01\
	 --config:controller.runId=$N-$V-1pct\
	 --config:controller.outputDirectory=./output/$N-$V-1pct\
	 --config:controller.lastIteration=$(LAST_IT)

# Run at 0.1pct sample (the downsampled population file is named "0pct").
run-0pct: input/prepare-config.yml
	$(sc) --config $<\
	 --config:plans.inputPlansFile=before-calibration/output/$N-$V-0pct.plans-initial.xml.gz\
	 --config:qsim.flowCapacityFactor=0.001\
	 --config:qsim.storageCapacityFactor=0.001\
	 --config:counts.countsScaleFactor=0.001\
	 --config:simwrapper.sampleSize=0.001\
	 --config:controller.runId=$N-$V-0pct\
	 --config:controller.outputDirectory=./output/$N-$V-0pct\
	 --config:controller.lastIteration=$(LAST_IT)
