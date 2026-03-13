# External data change log

This file documents the changes to the external files (e.g., network, population, transit schedules), that are not tracked in the repository. 

Changes in version v1.1
1. The public transport schedule is updated:
- A new shapefile is used to extract the network and routes for regional trains (i.e., train_short).
	(Sachsen + 40km buffer) intersect with boudary of Germany
	The shapefile is uploaded to shared-svn: https://svn.vsp.tu-berlin.de/repos/shared-svn/projects/agimo/data/dresden-model/shp/shp-for-regional-trains-utm32n.shp
- The naming of the 3 pt category is updated to reduce confusion:
	regio -> local
	train_short -> regional_train (e.g, S-Bahn, RE, RB)
	train_long -> long_distance_train

2. The network is updated based on the new public transport schedule. 

3. The capacity of the bridges are fixed
- Marienbrücke -> reduce the capacity to make it consistent with other bridges
- Carolabrücke -> reduce the capacity to make it consistent with other bridges; reduce capacity to match the actual situation before the collapse (i.e., 1 lane per direction, due to the closure of the other direction)


4. The Augustus bridge is closed for car and freight. Bike is still allowed. PT is not impacted as it use a seperate network. 

After 1 + 2, the network version is v1.1
After 1 + 2 + 3 + 4, the network version is v1.1.1