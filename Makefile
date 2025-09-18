
N := dresden
V := v1.0
CRS := EPSG:25832

ifndef SUMO_HOME
	export SUMO_HOME := $(abspath ../../Sumo/)
endif

#define some important paths
# osmosis and sumo paths need to be in " because of blank space in path...
osmosis := "C:/Program Files/osmosis-0.49.2//bin/osmosis.bat"
germany := $(CURDIR)/../../shared-svn/projects/matsim-germany
shared := $(CURDIR)/../../shared-svn/projects/agimo
sharedOberlausitzDresden := $(CURDIR)/../../shared-svn/projects/matsim-oberlausitz-dresden
sharedLausitz := $(CURDIR)/../../shared-svn/projects/DiTriMo
dresden := $(CURDIR)/../../public-svn/matsim/scenarios/countries/de/dresden/dresden-$V/input/

MEMORY ?= 50G
#JAR := matsim-$(N)-*.jar
JAR := matsim-dresden-1.0-e9c303a-dirty.jar
NETWORK := $(germany)/maps/germany-250127.osm.pbf

# Scenario creation tool
sc := java -Xms$(MEMORY) -Xmx$(MEMORY) -jar $(JAR)

.PHONY: prepare

$(JAR):
	mvn package -DskipTests

######################################### network creation ############################################################################################

# Required files
#this step is only necessary once. The downloaded network is uploaded to shared-svn/projects/matsim-germany/maps
#input/network.osm.pbf:
#	curl https://download.geofabrik.de/europe/germany-250127.osm.pbf\
#	  -o ../../shared-svn/projects/matsim-germany/maps/germany-250127.osm.pbf

#retrieve detailed network (see param highway) from OSM
#input/network-detailed.osm.pbf: $(NETWORK)
#	$(osmosis) --rb file=$<\
#	 --tf accept-ways bicycle=yes highway=motorway,motorway_link,trunk,trunk_link,primary,primary_link,secondary_link,secondary,tertiary,motorway_junction,residential,unclassified,living_street,service\
#	 --bounding-polygon file="$(sharedOberlausitzDresden)/data/dresden.poly"\
#	 --used-node --wb $@

#	retrieve coarse network (see param highway) from OSM
# the .poly files contain point coords.
#it is rather painful to create them. My workflow is the following:
# 1) create points layer in QGIS with points depicting your boundary area.
# 2) it is important that the points are ordered, so add an id column and number them in increasing order as you go around your area and create the points.
# 3) ad x/y coords as feature attributes: Vector - Geometry Tools - Add Geometry Attributes.
# 4) Export as csv and copy content of csv without the id column to a .poly file.
# see https://wiki.openstreetmap.org/wiki/Osmosis/Polygon_Filter_File_Format for .poly structure
#input/network-coarse.osm.pbf: $(NETWORK)
#	$(osmosis) --rb file=$<\
#	 --tf accept-ways highway=motorway,motorway_link,trunk,trunk_link,primary,primary_link,secondary_link,secondary,tertiary,motorway_junction\
#	 --bounding-polygon file="$(shared)/data/dresden-model/dresden-extended.poly"\
#	 --used-node --wb $@

  #	retrieve germany wide network (see param highway) from OSM
#input/network-germany.osm.pbf: $(NETWORK)
#	$(osmosis) --rb file=$<\
# 	 --tf accept-ways highway=motorway,motorway_link,motorway_junction,trunk,trunk_link,primary,primary_link\
# 	 --used-node --wb $@

input/network.osm: input/network-germany.osm.pbf input/network-coarse.osm.pbf input/network-detailed.osm.pbf
	$(osmosis) --rb file=$< --rb file=$(word 2,$^) --rb file=$(word 3,$^)\
  	 --merge --merge\
  	 --tag-transform file=input/remove-railway.xml\
  	 --wx $@


#	roadTypes are taken either from the general file "osmNetconvert.typ.xml"
#	or from the german one "osmNetconvertUrbanDe.ty.xml"
input/sumo.net.xml: ./input/network.osm
	$(SUMO_HOME)/bin/netconvert --geometry.remove --ramps.guess --ramps.no-split\
	 --type-files $(SUMO_HOME)/data/typemap/osmNetconvert.typ.xml,$(SUMO_HOME)/data/typemap/osmNetconvertUrbanDe.typ.xml\
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
# TODO: talk to GR about freespeed factor/solutions to counts problem in RVR scenario.
input/v1.0/dresden-v1.0-network.xml.gz: input/sumo.net.xml
	echo input/$V/$N-$V-network.xml.gz
	$(sc) prepare network-from-sumo $< --output $@ --free-speed-factor 0.7
	$(sc) prepare clean-network $@ --output $@ --modes car --modes bike --modes ride --remove-turn-restrictions
#	delete truck as allowed mode (not used), add longDistanceFreight as allowed mode, prepare network for emissions analysis
	$(sc) prepare network\
	 --network $@\
	 --output $@

# gtfs data from 20230113 used because it has way more pt lines in lausitz area than more recent one in shared-svn/matsim-oberlausitz-dresden
# this might not be relevant anymore for this specific model (dresden only), but out of convenience it wont be altered. -sm0925
input/v1.0/dresden-v1.0-network-with-pt.xml.gz: input/$V/$N-$V-network.xml.gz
	$(sc) prepare transit-from-gtfs --network $<\
	 --output=input/$V\
	 --name $N-$V --date "2023-01-11" --target-crs $(CRS) \
	 $(sharedLausitz)/data/gtfs/20230113_regio.zip\
	 $(sharedLausitz)/data/gtfs/20230113_train_short.zip\
	 $(sharedLausitz)/data/gtfs/20230113_train_long.zip\
	 --prefix regio_,short_,long_\
	 --shp $(shared)/data/dresden-model/shp/dresden-pt-area-utm32n.shp\
	 --shp $(shared)/data/dresden-model/shp/dresden-pt-area-utm32n.shp\
	 --shp $(germany)/shp/germany-area.shp\


########################### population creation ######################################################################################

# extract dresden long haul freight traffic trips from german wide file
input/plans-longHaulFreight.xml.gz:
	$(sc) prepare extract-freight-trips ../../public-svn/matsim/scenarios/countries/de/german-wide-freight/v2/german_freight.100pct.plans.xml.gz\
	 --network ../../public-svn/matsim/scenarios/countries/de/german-wide-freight/v2/germany-europe-network.xml.gz\
	 --input-crs $(CRS)\
	 --target-crs $(CRS)\
	 --shp $(shared)/data/dresden-model/shp/dresden-utm32n.shp\
	 --shp-crs $(CRS)\
	 --cut-on-boundary\
	 --legMode "truck40t"\
	 --subpopulation "longDistanceFreight"\
	 --output $@

# trajectory-to-plans formerly was a collection of methods to prepare a given population
# now, most of the functions of this class do have their own class (downsample, splitduration types...)
# it basically only transforms the old attribute format to the new one
# --max-typical-duration set to 0 because this switches off the duration split, which we do later
input/v1.0/prepare-100pct.plans.xml.gz:
	$(sc) prepare trajectory-to-plans\
	 --name prepare --sample-size 1 --output input/$V\
	 --max-typical-duration 0\
	 --population $(sharedOberlausitzDresden)/data/snz/20250123_Teilmodell_Hoyerswerda/Modell/population.xml.gz\
	 --attributes $(sharedOberlausitzDresden)/data/snz/20250130_Teilmodell_Hoyerswerda/Modell/personAttributes.xml.gz
# resolve senozon aggregated grid coords (activities): distribute them based on landuse.shp
	$(sc) prepare resolve-grid-coords $@\
	 --input-crs $(CRS)\
	 --grid-resolution 300\
	 --landuse $(germany)/landuse/landuse.shp\
	 --output $@

 input/v1.0/dresden-v1.0-100pct.plans-initial.xml.gz: input/plans-longHaulFreight.xml.gz input/$V/prepare-100pct.plans.xml.gz
# generate some short distance trips, which in senozon data generally are missing
 # trip range 700m because:
 # when adding 1km trips (default value), too many trips of bin 1km-2km were also added.
 #the range value is beeline, so the trip distance (routed) often is higher than 1km
 #TODO: here, we need to differ between dresden and oberlausitz-dresden population for different calibs. One is based on Srv, the other on MiD.
# MiD: --num-trips 210199
	$(sc) prepare generate-short-distance-trips\
   	 --population $(word 2,$^)\
   	 --input-crs $(CRS)\
#   	 TODO: use dd shp, run python script for retrieving --num-trips
  	 --shp $(shared)/data/oberlausitz-area/oberlausitz.shp --shp-crs $(CRS)\
  	 --range 700\
    --num-trips 210199
#	adapt coords of activities in the wider network such that they are closer to a link
# 	such that agents do not have to walk as far as before
	$(sc) prepare adjust-activity-to-link-distances input/$V/prepare-100pct.plans.xml.gz\
	 --shp $(shared)/data/oberlausitz-area/oberlausitz.shp --shp-crs $(CRS)\
     --scale 1.15\
     --input-crs $(CRS)\
     --network input/$V/$N-$V-network.xml.gz\
     --output input/$V/prepare-100pct.plans-adj.xml.gz
#	change modes in subtours with chain based AND non-chain based by choosing mode for subtour randomly
	$(sc) prepare fix-subtour-modes --coord-dist 100 --input input/$V/prepare-100pct.plans-adj.xml.gz --output $@
#	set car availability for agents below 18 to false, standardize some person attrs, set home coords, set person income
	$(sc) prepare population $@ --output $@
#	split activity types to type_duration for the scoring to take into account the typical duration
	$(sc) prepare split-activity-types-duration\
		--input $@\
		--exclude commercial_start,commercial_end,freight_start,freight_end,service\
		--output $@
#	merge person and freight pops
	$(sc) prepare merge-populations $@ $< --output $@
	$(sc) prepare adapt-freight-plans $@ --output $@
	$(sc) prepare downsample-population $@\
    	 --sample-size 1\
    	 --samples 0.25 0.1 0.01 0.001\

#TODO: create facilities from plans and use as input

# create matsim counts file
input/v1.0/dresden-v1.0-counts-bast.xml.gz: input/$V/$N-$V-network-with-pt.xml.gz
	$(sc) prepare counts-from-bast\
		--network $<\
		--motorway-data $(germany)/bast-counts/2019/2019_A_S.zip\
		--primary-data $(germany)/bast-counts/2019/2019_B_S.zip\
		--station-data $(germany)/bast-counts/2019/Jawe2019.csv\
		--year 2019\
		--shp $(shared)/data/oberlausitz-area/oberlausitz.shp --shp-crs $(CRS)\
		--output $@

# output of check-population was compared to initial output in matsim-oberlausitz-dresden scenario documentation, they align -sm0225
check: input/$V/$N-$V-100pct.plans-initial.xml.gz
	$(sc) analysis check-population $<\
 	 --input-crs $(CRS)\
	 --shp $(shared)/data/oberlausitz-area/oberlausitz.shp --shp-crs $(CRS)

# Aggregated target
prepare: input/$V/$N-$V-100pct.plans-initial.xml.gz input/$V/$N-$V-network-with-pt.xml.gz
	echo "Done"