package org.matsim.run.prepare;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkWriter;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.*;
import org.matsim.pt.utils.TransitScheduleValidator;
import org.matsim.vehicles.*;
import picocli.CommandLine;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class AddCampusbahnToScenario implements MATSimAppCommand {
	private static final Logger log = LogManager.getLogger(AddCampusbahnToScenario.class);

	private static final double CAMPUSBAHN_FREESPEED = 120.0;

	@CommandLine.Option(names = "--network", description = "Path to network file", required = true)
	private String networkFile;

	@CommandLine.Option(names = "--transit-vehicles", description = "Path to transit vehicles file", required = true)
	private String transitVehiclesFile;

	@CommandLine.Option(names = "--transit-schedule", description = "Path to transit schedule file", required = true)
	private String transitScheduleFile;

	@CommandLine.Option(names = "--output", description = "Output path of the prepared network", required = true)
	private String outputPath;

	public static void main(String[] args) {
		new AddCampusbahnToScenario().execute(args);
	}

	@Override
	public Integer call() throws Exception {
		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());

		new TransitScheduleReader(scenario).readFile(transitScheduleFile);
		new MatsimNetworkReader(scenario.getNetwork()).readFile(networkFile);
		new MatsimVehicleReader(scenario.getTransitVehicles()).readFile(transitVehiclesFile);

		addCampusbahn(scenario);

		new NetworkWriter(scenario.getNetwork()).write(Paths.get(outputPath, "matsim-dresden-v1.0.network-with-CaBa-5min-FAST-fixed.xml.gz").toString());
		log.info("Network including Campusbahn written to {}", Paths.get(outputPath, "matsim-dresden-v1.0.network-with-CaBa-5min-FAST-fixed.xml.gz"));

		new TransitScheduleWriter(scenario.getTransitSchedule()).writeFile(Paths.get(outputPath, "matsim-dresden-v1.0-transitSchedule-with-CaBa-5min-FAST-fixed.xml.gz").toString());
		log.info("Transit schedule including Campusbahn written to {}", Paths.get(outputPath, "matsim-dresden-v1.0-transitSchedule-with-CaBa-5min-FAST-fixed.xml.gz"));

		new MatsimVehicleWriter(scenario.getTransitVehicles()).writeFile(Paths.get(outputPath, "matsim-dresden-v1.0-transitVehicles-with-CaBa-5min-FAST-fixed.xml.gz").toString());
		log.info("Transit vehicles including Campusbahn written to {}", Paths.get(outputPath, "matsim-dresden-v1.0-transitVehicles-with-CaBa-5min-FAST-fixed.xml.gz"));

		return 0;
	}

	// ---------- helpers ----------

	private static VehicleType requireVehicleType(Vehicles vehicles, String typeId) {
		VehicleType t = vehicles.getVehicleTypes().get(Id.create(typeId, VehicleType.class));
		if (t == null) {
			throw new IllegalStateException("VehicleType not found: " + typeId
				+ " (available: " + vehicles.getVehicleTypes().keySet() + ")");
		}
		return t;
	}

	private static TransitStopFacility requireFacility(TransitSchedule schedule, String existingFacilityId) {
		TransitStopFacility f = schedule.getFacilities().get(Id.create(existingFacilityId, TransitStopFacility.class));
		if (f == null) {
			throw new IllegalStateException("TransitStopFacility not found in schedule: " + existingFacilityId);
		}
		if (f.getLinkId() == null) {
			throw new IllegalStateException("TransitStopFacility has no linkId: " + existingFacilityId);
		}
		return f;
	}

	private static TransitStopFacility cloneFacilityWithNewId(
		TransitSchedule schedule,
		TransitScheduleFactory factory,
		Network network,
		String existingFacilityId,
		String newFacilityId
	) {
		TransitStopFacility src = requireFacility(schedule, existingFacilityId);

		if (!network.getLinks().containsKey(src.getLinkId())) {
			throw new IllegalStateException("Schedule facility " + existingFacilityId
				+ " references linkId " + src.getLinkId() + " which is NOT in the network.");
		}

		TransitStopFacility dst = factory.createTransitStopFacility(
			Id.create(newFacilityId, TransitStopFacility.class),
			src.getCoord(),
			src.getIsBlockingLane()
		);

		dst.setName(src.getName());
		dst.setLinkId(src.getLinkId());

		schedule.addStopFacility(dst);
		return dst;
	}

	private static void forceFacilityLink(Network network, TransitStopFacility f, String linkId) {
		Id<Link> id = Id.createLinkId(linkId);
		if (!network.getLinks().containsKey(id)) {
			throw new IllegalStateException("Wanted to set facility " + f.getId() + " to link " + linkId
				+ " but link not found in network.");
		}
		f.setLinkId(id);
	}

	private static List<Id<Link>> compactConsecutive(List<Id<Link>> in) {
		List<Id<Link>> out = new ArrayList<>(in.size());
		Id<Link> prev = null;
		for (Id<Link> id : in) {
			if (id == null) throw new IllegalStateException("null linkId in route list");
			if (!id.equals(prev)) out.add(id);
			prev = id;
		}
		return out;
	}

	private static Link requireLink(Network network, String linkId) {
		Link l = network.getLinks().get(Id.createLinkId(linkId));
		if (l == null) throw new IllegalStateException("Link not found in network: " + linkId);
		return l;
	}

	private static void setFreespeedForLinks(List<Link> links, double freespeed, String label) {
		int changed = 0;
		for (Link l : links) {
			double old = l.getFreespeed();
			if (old != freespeed) {
				l.setFreespeed(freespeed);
				changed++;
				log.info("{}: set freespeed on {} old={} m/s -> new={} m/s", label, l.getId(), old, freespeed);
			}
		}
		log.info("{}: set freespeed={} m/s on {} links (changed={})", label, freespeed, links.size(), changed);
	}

	// ---------- main logic ----------

	public void addCampusbahn(Scenario scenario) {
		Network network = scenario.getNetwork();
		TransitSchedule schedule = scenario.getTransitSchedule();
		TransitScheduleFactory scheduleFactory = schedule.getFactory();
		Vehicles transitVehicles = scenario.getTransitVehicles();

		VehicleType vehicleTypeTram = requireVehicleType(transitVehicles, "Tram_veh_type");

		// ---- existing PT links (fahr-links) ----
		Link QueralleeStrehlen = requireLink(network, "pt_2185");
		Link StrehlenQuerallee = requireLink(network, "pt_2136");

		Link StrehlenWasaplatz = requireLink(network, "pt_6126");
		Link WasaplatzStrehlen = requireLink(network, "pt_6144");

		Link WasaplatzCDFriedrichStr = requireLink(network, "pt_6127");
		Link CDFriedrichStrWasaplatz = requireLink(network, "pt_6143");

		Link CDFriedrichStrZellescherWeg = requireLink(network, "pt_6128");
		Link ZellescherWegCDFriedrichStr = requireLink(network, "pt_6142");

		Link ZellescherWegSLUB = requireLink(network, "pt_6129");
		Link SLUBZellescherWeg = requireLink(network, "pt_6141");

		Link SLUBTU = requireLink(network, "pt_6130");
		Link TUSLUB = requireLink(network, "pt_6140");

		Link TUNuernbergerPlatz = requireLink(network, "pt_6131");
		Link NuernbergerPlatzTU = requireLink(network, "pt_6139");

		Link NuernbergerPlatzBernhardstr = requireLink(network, "pt_3678");
		Link BernhardstrNuernbergerPlatz = requireLink(network, "pt_6138");

		Link BernhardstrChemnitzerStr = requireLink(network, "pt_3679");
		Link ChemnitzerStrBernhardstr = requireLink(network, "pt_3668");

		Link ChemnitzerStrZwickauerStr = requireLink(network, "pt_6132");
		Link ZwickauerStrChemnitzerStr = requireLink(network, "pt_6137");

		Link ZwickauerStrFabrikstr = requireLink(network, "pt_6133");
		Link FabrikstrZwickauerStr = requireLink(network, "pt_6136");

		Link FabrikstrTharandterStr = requireLink(network, "pt_6134");
		Link TharandterStrFabrikstr = requireLink(network, "pt_6135");

		// ---- clone stop facilities ----
		TransitStopFacility stopFacility1EW  = cloneFacilityWithNewId(schedule, scheduleFactory, network, "regio_373641",   "QueralleeEW");
		TransitStopFacility stopFacility2EW  = cloneFacilityWithNewId(schedule, scheduleFactory, network, "regio_49237",    "StrehlenEW");
		TransitStopFacility stopFacility3EW  = cloneFacilityWithNewId(schedule, scheduleFactory, network, "regio_136125",   "WasaplatzEW");
		TransitStopFacility stopFacility4EW  = cloneFacilityWithNewId(schedule, scheduleFactory, network, "regio_10793",    "CDFriedrichstrEW");
		TransitStopFacility stopFacility5EW  = cloneFacilityWithNewId(schedule, scheduleFactory, network, "regio_185242",   "ZellescherwegEW");
		TransitStopFacility stopFacility6EW  = cloneFacilityWithNewId(schedule, scheduleFactory, network, "regio_207912",   "SlubEW");
		TransitStopFacility stopFacility7EW  = cloneFacilityWithNewId(schedule, scheduleFactory, network, "regio_352921",   "TuEW");
		TransitStopFacility stopFacility8EW  = cloneFacilityWithNewId(schedule, scheduleFactory, network, "regio_72171.2",  "NuernbergerplatzEW");
		TransitStopFacility stopFacility9EW  = cloneFacilityWithNewId(schedule, scheduleFactory, network, "regio_19552",    "BernhardstrEW");
		TransitStopFacility stopFacility10EW = cloneFacilityWithNewId(schedule, scheduleFactory, network, "regio_83102",    "ChemnitzerstrEW");
		TransitStopFacility stopFacility11EW = cloneFacilityWithNewId(schedule, scheduleFactory, network, "regio_224745",   "ZwickauerstrEW");
		TransitStopFacility stopFacility12EW = cloneFacilityWithNewId(schedule, scheduleFactory, network, "regio_243075",   "FabrikstrEW");
		TransitStopFacility stopFacility13EW = cloneFacilityWithNewId(schedule, scheduleFactory, network, "regio_110446.4", "TharandterstrEW");

		TransitStopFacility stopFacility1WE  = cloneFacilityWithNewId(schedule, scheduleFactory, network, "regio_243875",   "QueralleeWE");
		TransitStopFacility stopFacility2WE  = cloneFacilityWithNewId(schedule, scheduleFactory, network, "regio_287069.1", "StrehlenWE");
		TransitStopFacility stopFacility3WE  = cloneFacilityWithNewId(schedule, scheduleFactory, network, "regio_136125.1", "WasaplatzWE");
		TransitStopFacility stopFacility4WE  = cloneFacilityWithNewId(schedule, scheduleFactory, network, "regio_27452",    "CDFriedrichstrWE");
		TransitStopFacility stopFacility5WE  = cloneFacilityWithNewId(schedule, scheduleFactory, network, "regio_269136",   "ZellescherwegWE");
		TransitStopFacility stopFacility6WE  = cloneFacilityWithNewId(schedule, scheduleFactory, network, "regio_328283",   "SlubWE");
		TransitStopFacility stopFacility7WE  = cloneFacilityWithNewId(schedule, scheduleFactory, network, "regio_256355",   "TuWE");
		TransitStopFacility stopFacility8WE  = cloneFacilityWithNewId(schedule, scheduleFactory, network, "regio_72171.3",  "NuernbergerplatzWE");
		TransitStopFacility stopFacility9WE  = cloneFacilityWithNewId(schedule, scheduleFactory, network, "regio_328859",   "BernhardstrWE");
		TransitStopFacility stopFacility10WE = cloneFacilityWithNewId(schedule, scheduleFactory, network, "regio_17521.1",  "ChemnitzerstrWE");
		TransitStopFacility stopFacility11WE = cloneFacilityWithNewId(schedule, scheduleFactory, network, "regio_398233",   "ZwickauerstrWE");
		TransitStopFacility stopFacility12WE = cloneFacilityWithNewId(schedule, scheduleFactory, network, "regio_268509",   "FabrikstrWE");
		TransitStopFacility stopFacility13WE = cloneFacilityWithNewId(schedule, scheduleFactory, network, "regio_378502",   "TharandterstrWE");

		// FIX: Start/End linkIds erzwingen (damit nicht der “vorherige” Link aus regio_* kommt)
		forceFacilityLink(network, stopFacility1EW,  "pt_2185");
		forceFacilityLink(network, stopFacility13EW, "pt_6134");
		forceFacilityLink(network, stopFacility13WE, "pt_6135");
		forceFacilityLink(network, stopFacility1WE,  "pt_2136");

		// ---- route stops ----
		List<TransitRouteStop> transitRouteStopsEW = List.of(
			scheduleFactory.createTransitRouteStop(stopFacility1EW, 0, 0),
			scheduleFactory.createTransitRouteStop(stopFacility2EW, 10, 10),
			scheduleFactory.createTransitRouteStop(stopFacility3EW, 20, 20),
			scheduleFactory.createTransitRouteStop(stopFacility4EW, 30, 30),
			scheduleFactory.createTransitRouteStop(stopFacility5EW, 40, 40),
			scheduleFactory.createTransitRouteStop(stopFacility6EW, 50, 50),
			scheduleFactory.createTransitRouteStop(stopFacility7EW, 60, 60),
			scheduleFactory.createTransitRouteStop(stopFacility8EW, 70, 70),
			scheduleFactory.createTransitRouteStop(stopFacility9EW, 80, 80),
			scheduleFactory.createTransitRouteStop(stopFacility10EW, 90, 90),
			scheduleFactory.createTransitRouteStop(stopFacility11EW, 100, 100),
			scheduleFactory.createTransitRouteStop(stopFacility12EW, 110, 110),
			scheduleFactory.createTransitRouteStop(stopFacility13EW, 120, 120)
		);

		List<TransitRouteStop> transitRouteStopsWE = List.of(
			scheduleFactory.createTransitRouteStop(stopFacility13WE, 0, 0),
			scheduleFactory.createTransitRouteStop(stopFacility12WE, 10, 10),
			scheduleFactory.createTransitRouteStop(stopFacility11WE, 20, 20),
			scheduleFactory.createTransitRouteStop(stopFacility10WE, 30, 30),
			scheduleFactory.createTransitRouteStop(stopFacility9WE, 40, 40),
			scheduleFactory.createTransitRouteStop(stopFacility8WE, 50, 50),
			scheduleFactory.createTransitRouteStop(stopFacility7WE, 60, 60),
			scheduleFactory.createTransitRouteStop(stopFacility6WE, 70, 70),
			scheduleFactory.createTransitRouteStop(stopFacility5WE, 80, 80),
			scheduleFactory.createTransitRouteStop(stopFacility4WE, 90, 90),
			scheduleFactory.createTransitRouteStop(stopFacility3WE, 100, 100),
			scheduleFactory.createTransitRouteStop(stopFacility2WE, 110, 110),
			scheduleFactory.createTransitRouteStop(stopFacility1WE, 120, 120)
		);

		transitRouteStopsEW.forEach(s -> s.setAwaitDepartureTime(true));
		transitRouteStopsWE.forEach(s -> s.setAwaitDepartureTime(true));

		// ---- HARD-CODED: set freespeed on Campusbahn links (both directions) ----
		List<Link> campusbahnLinks = List.of(
			// EW direction links
			QueralleeStrehlen,
			StrehlenWasaplatz,
			WasaplatzCDFriedrichStr,
			CDFriedrichStrZellescherWeg,
			ZellescherWegSLUB,
			SLUBTU,
			TUNuernbergerPlatz,
			NuernbergerPlatzBernhardstr,
			BernhardstrChemnitzerStr,
			ChemnitzerStrZwickauerStr,
			ZwickauerStrFabrikstr,
			FabrikstrTharandterStr,

			// WE direction links
			TharandterStrFabrikstr,
			FabrikstrZwickauerStr,
			ZwickauerStrChemnitzerStr,
			ChemnitzerStrBernhardstr,
			BernhardstrNuernbergerPlatz,
			NuernbergerPlatzTU,
			TUSLUB,
			SLUBZellescherWeg,
			ZellescherWegCDFriedrichStr,
			CDFriedrichStrWasaplatz,
			WasaplatzStrehlen,
			StrehlenQuerallee
		);

		setFreespeedForLinks(campusbahnLinks, CAMPUSBAHN_FREESPEED, "Campusbahn");

		// ---- network routes (Zwischenlinks only) ----
		Id<Link> startEW = stopFacility1EW.getLinkId();   // pt_2185
		Id<Link> endEW   = stopFacility13EW.getLinkId();  // pt_6134

		List<Id<Link>> rawEW = List.of(
			StrehlenWasaplatz.getId(),
			WasaplatzCDFriedrichStr.getId(),
			CDFriedrichStrZellescherWeg.getId(),
			ZellescherWegSLUB.getId(),
			SLUBTU.getId(),
			TUNuernbergerPlatz.getId(),
			NuernbergerPlatzBernhardstr.getId(),
			BernhardstrChemnitzerStr.getId(),
			ChemnitzerStrZwickauerStr.getId(),
			ZwickauerStrFabrikstr.getId()
		);

		NetworkRoute networkRouteEW = RouteUtils.createLinkNetworkRouteImpl(
			startEW,
			compactConsecutive(rawEW),
			endEW
		);

		Id<Link> startWE = stopFacility13WE.getLinkId();  // pt_6135
		Id<Link> endWE   = stopFacility1WE.getLinkId();   // pt_2136

		List<Id<Link>> rawWE = List.of(
			FabrikstrZwickauerStr.getId(),
			ZwickauerStrChemnitzerStr.getId(),
			ChemnitzerStrBernhardstr.getId(),
			BernhardstrNuernbergerPlatz.getId(),
			NuernbergerPlatzTU.getId(),
			TUSLUB.getId(),
			SLUBZellescherWeg.getId(),
			ZellescherWegCDFriedrichStr.getId(),
			CDFriedrichStrWasaplatz.getId(),
			WasaplatzStrehlen.getId()
		);

		NetworkRoute networkRouteWE = RouteUtils.createLinkNetworkRouteImpl(
			startWE,
			compactConsecutive(rawWE),
			endWE
		);

		// ---- create transit routes ----
		TransitRoute transitRouteEW = scheduleFactory.createTransitRoute(
			Id.create("CaBaEW", TransitRoute.class),
			networkRouteEW,
			transitRouteStopsEW,
			TransportMode.pt
		);

		TransitRoute transitRouteWE = scheduleFactory.createTransitRoute(
			Id.create("CaBaWE", TransitRoute.class),
			networkRouteWE,
			transitRouteStopsWE,
			TransportMode.pt
		);

		// ---- departures & vehicles ----
		addDeparturesAndTransitVehicles(transitRouteEW, "CaBa_vehicleEW_", scheduleFactory, transitVehicles, vehicleTypeTram);
		addDeparturesAndTransitVehicles(transitRouteWE, "CaBa_vehicleWE_", scheduleFactory, transitVehicles, vehicleTypeTram);

		// ---- lines ----
		TransitLine transitLineEW = scheduleFactory.createTransitLine(Id.create("CaBaEW", TransitLine.class));
		transitLineEW.addRoute(transitRouteEW);
		schedule.addTransitLine(transitLineEW);

		TransitLine transitLineWE = scheduleFactory.createTransitLine(Id.create("CaBaWE", TransitLine.class));
		transitLineWE.addRoute(transitRouteWE);
		schedule.addTransitLine(transitLineWE);

		// ---- validate ----
		TransitScheduleValidator.ValidationResult checkResult = TransitScheduleValidator.validateAll(schedule, network);
		if (!checkResult.getWarnings().isEmpty()) {
			log.warn("TransitScheduleValidator warnings:\n{}", String.join("\n", checkResult.getWarnings()));
		}

		if (checkResult.isValid()) {
			log.info("TransitSchedule and Network valid according to TransitScheduleValidator");
		} else {
			log.error("TransitScheduleValidator errors:\n{}", String.join("\n", checkResult.getErrors()));
			throw new IllegalStateException("TransitSchedule and/or Network invalid");
		}
	}

	private void addDeparturesAndTransitVehicles(
		TransitRoute transitRoute,
		String vehicleIdPrefix,
		TransitScheduleFactory scheduleFactory,
		Vehicles transitVehicles,
		VehicleType vehicleType
	) {
		if (vehicleType == null) throw new IllegalStateException("vehicleType is null");

		for (int i = 6 * 3600; i < 24 * 3600; i += 300) {
			Departure departure = scheduleFactory.createDeparture(Id.create("departure_" + i, Departure.class), i);

			Vehicle vehicle = transitVehicles.getFactory().createVehicle(
				Id.createVehicleId(vehicleIdPrefix + "100" + i),
				vehicleType
			);

			departure.setVehicleId(vehicle.getId());

			transitVehicles.addVehicle(vehicle);
			transitRoute.addDeparture(departure);
		}
	}
}
