package org.matsim.dashboards;

import org.matsim.core.config.Config;
import org.matsim.simwrapper.Dashboard;
import org.matsim.simwrapper.DashboardProvider;
import org.matsim.simwrapper.SimWrapper;
import org.matsim.simwrapper.dashboard.EmissionsDashboard;
import org.matsim.simwrapper.dashboard.TripDashboard;

import java.util.List;

/**
 * Default Dashboards for the Dresden scenario.
 */
public class DresdenDashboardProvider implements DashboardProvider {
	@Override
	public List<Dashboard> getDashboards(Config config, SimWrapper simWrapper) {
//		create TripDashboard with reference files for calibration
//		this has to be generic type Dashboard because otherwise we cannot use Dashboard.customize(...).context("calibration")
//		we need to set the context here, otherwise simwrapper adds the default TripDashboard without reference files.
		Dashboard trips = Dashboard.customize(new TripDashboard(
			"mode_share_ref.csv",
			"mode_share_per_dist_ref.csv",
			"mode_users_ref.csv")
			.withGroupedRefData("mode_share_per_group_dist_ref.csv", "age", "economic_status", "income", "employment")
			.withDistanceDistribution("mode_share_distance_distribution.csv")
			.setAnalysisArgs("--person-filter", "subpopulation=person")).context("calibration").title("Trips (calibration)");

		return List.of(trips,
			new EmissionsDashboard(config.global().getCoordinateSystem())
//			the NoiseAnalysis is not run here because it needs more RAM than the entire simulation,
//			which leads to VM crashes and prevents other analysis to run. We have to run it separately (e.g. with DresdenSimWrapperRunner)
		);
	}
}
