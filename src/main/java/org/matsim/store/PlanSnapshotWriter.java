package org.matsim.store;

import org.apache.parquet.conf.ParquetConfiguration;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.api.WriteSupport;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.LocalOutputFile;
import org.apache.parquet.io.OutputFile;
import org.apache.parquet.io.api.Binary;
import org.apache.parquet.io.api.RecordConsumer;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.MessageTypeParser;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.config.groups.ControllerConfigGroup;
import org.matsim.core.controler.events.BeforeMobsimEvent;
import org.matsim.core.controler.listener.BeforeMobsimListener;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.utils.misc.OptionalTime;
import org.matsim.utils.objectattributes.ObjectAttributesConverter;
import org.matsim.utils.objectattributes.attributable.Attributable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Writes a plan-memory snapshot to parquet, straight against the parquet schema in
 * {@code src/main/resources/plans.parquet.schema}.
 *
 * There is no intermediate schema language and no intermediate object layer: the schema is parsed
 * with {@link MessageTypeParser} and MATSim's own objects are streamed field by field into parquet's
 * {@link RecordConsumer}. Parquet is what we want, so parquet is what we describe. (An earlier
 * version went through an Avro schema and {@code parquet-avro}; that worked, but it made Avro the
 * contract for a file nobody reads with Avro, it stamped Avro's names and its deprecated
 * MAP_KEY_VALUE annotation into the output, and it allocated a GenericRecord plus boxed values for
 * every activity, leg and route on the way.)
 *
 * GRAIN: one row per plan, whose elements are ONE ordered {@code elements[]} list -- activities and
 * legs interleaved, exactly as {@code <plan>} holds one interleaved child sequence in plans.xml. The
 * array order is the order; recover the index with {@code generate_subscripts(elements, 1)}
 * alongside the UNNEST. See the schema file, which carries the full field-level contract.
 *
 * Streaming is the other reason this exists: rows go to the file as they are built, so memory stays
 * flat in the population size. The older {@link PlanSnapshotWriterDuckDB} materializes the whole
 * snapshot (staging tables, then list()/struct_pack aggregates) before writing anything -- measured
 * on the 1pct Dresden output plans, ~1.2 s and a few MB of extra RSS per 44k plans here, linear,
 * versus 13-36 s and 5-9 GB there, which hits DuckDB's memory limit above ~200k plans. That matters
 * most for the BeforeMobsim snapshot below, which competes for heap with the live population.
 *
 * Runs Hadoop-free: parquet-mr &gt;= 1.15 offers {@link LocalOutputFile} plus
 * {@link PlainParquetConfiguration}, so no {@code hadoop-common} on the classpath is needed.
 *
 * DICTIONARY: dictionary encoding is deliberately OFF, which is the opposite of the usual advice.
 * Route link ids are ~54% of the file and are highly redundant (routes share long stretches of
 * links), so zstd gets ~12:1 on the raw strings; dictionary-encoding them replaces that redundancy
 * with near-random indices that only compress ~2:1. Measured on the 1pct Dresden output plans the
 * links column went from 6.2 MB to 8.0 MB compressed even though it shrank from 74 MB to 17 MB
 * uncompressed, and read speed was identical either way.
 *
 * Fires at BeforeMobsim on exactly the iterations MATSim dumps the regular plans XML -- see
 * {@link PlanSnapshotWriterDuckDB#writesOnIteration(int, int, int)}, whose schedule this shares.
 */
public final class PlanSnapshotWriter implements BeforeMobsimListener {

	private static final MessageType SCHEMA = loadSchema();

	/** The same attribute rendering the plans XML writer uses; see {@link #attributeValue(Object)}. */
	private static final ObjectAttributesConverter CONVERTER = new ObjectAttributesConverter();
	/** Per class: does CONVERTER handle it? Absent means "not asked yet". */
	private static final Map<Class<?>, Boolean> CONVERTIBLE = new ConcurrentHashMap<>();

	public PlanSnapshotWriter() {
	}

	private static MessageType loadSchema() {
		try (InputStream in = PlanSnapshotWriter.class.getResourceAsStream("/plans.parquet.schema")) {
			if (in == null) {
				throw new IllegalStateException("plans.parquet.schema not found on the classpath");
			}
			// MessageTypeParser has no comment syntax, but the contract belongs next to the schema.
			String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("(?m)//.*$", "");
			return MessageTypeParser.parseMessageType(text);
		} catch (IOException e) {
			throw new UncheckedIOException("cannot read plans.parquet.schema", e);
		}
	}

	@Override
	public void notifyBeforeMobsim(BeforeMobsimEvent event) {
		int iteration = event.getIteration();
		ControllerConfigGroup cfg = event.getServices().getConfig().controller();
		if (!PlanSnapshotWriterDuckDB.writesOnIteration(iteration, cfg.getWritePlansInterval(), cfg.getWritePlansUntilIteration())) {
			return;
		}
		Population population = event.getServices().getScenario().getPopulation();
		String outPath = event.getServices().getControllerIO().getIterationFilename(iteration, "plans.parquet");
		write(population, outPath);
	}

	/** Snapshot {@code population} to a single parquet file at {@code outPath}. */
	public void write(Population population, String outPath) {
		List<Person> persons = new ArrayList<>(population.getPersons().values());
		persons.sort(Comparator.comparing(p -> p.getId().toString())); // stable row order, like the DuckDB writer's ORDER BY

		try (ParquetWriter<PlanRow> writer = new Builder(new LocalOutputFile(Paths.get(outPath)))
			.withConf(new PlainParquetConfiguration()) // keeps org.apache.hadoop.conf.Configuration off the code path
			.withCompressionCodec(CompressionCodecName.ZSTD)
			.withCodecFactory(new ZstdCodecFactory()) // parquet-mr's own CodecFactory would pull in the Hadoop runtime
			.withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
			.withDictionaryEncoding(false) // see the DICTIONARY note above
			.build()) {

			PlanRow row = new PlanRow();
			for (Person p : persons) {
				row.person = p;
				row.personAttributes = attributes(p);
				Plan selected = p.getSelectedPlan();
				int planIdx = 0;
				for (Plan pl : p.getPlans()) {
					row.plan = pl;
					row.planIdx = planIdx++;
					row.selected = pl == selected;
					writer.write(row); // consumed synchronously; one holder is enough
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException("parquet snapshot failed for " + outPath, e);
		}
	}

	/** What one row is made of. Mutable and reused: {@link ParquetWriter#write} consumes it before returning. */
	private static final class PlanRow {
		Person person;
		Plan plan;
		int planIdx;
		boolean selected;
		Map<String, String> personAttributes;
	}

	private static final class Builder extends ParquetWriter.Builder<PlanRow, Builder> {
		private Builder(OutputFile file) {
			super(file);
		}

		@Override
		protected Builder self() {
			return this;
		}

		@Override
		protected WriteSupport<PlanRow> getWriteSupport(org.apache.hadoop.conf.Configuration conf) {
			return new PlanWriteSupport();
		}

		@Override
		protected WriteSupport<PlanRow> getWriteSupport(ParquetConfiguration conf) {
			return new PlanWriteSupport();
		}
	}

	/**
	 * Writes a {@link PlanRow} against {@link #SCHEMA}. Every startField/endField carries the field's
	 * INDEX in its group -- that index is what parquet resolves against; the name is for diagnostics.
	 * So the order of the calls here must track the schema file field for field.
	 */
	private static final class PlanWriteSupport extends WriteSupport<PlanRow> {

		private RecordConsumer rc;

		@Override
		public WriteContext init(org.apache.hadoop.conf.Configuration configuration) {
			return new WriteContext(SCHEMA, new HashMap<>());
		}

		@Override
		public WriteContext init(ParquetConfiguration configuration) {
			return new WriteContext(SCHEMA, new HashMap<>());
		}

		@Override
		public void prepareForWrite(RecordConsumer recordConsumer) {
			this.rc = recordConsumer;
		}

		@Override
		public void write(PlanRow row) {
			rc.startMessage();
			required("personId", 0, row.person.getId().toString());
			rc.startField("planIdx", 1);
			rc.addInteger(row.planIdx);
			rc.endField("planIdx", 1);
			rc.startField("selected", 2);
			rc.addBoolean(row.selected);
			rc.endField("selected", 2);
			optional("score", 3, row.plan.getScore());
			map("personAttributes", 4, row.personAttributes);
			map("planAttributes", 5, attributes(row.plan));
			elements(6, row.plan);
			rc.endMessage();
		}

		/** {@code required group elements (LIST) { repeated group list { required group element { ... } } }} */
		private void elements(int index, Plan plan) {
			rc.startField("elements", index);
			rc.startGroup();
			List<? extends PlanElement> planElements = plan.getPlanElements();
			if (!planElements.isEmpty()) {
				rc.startField("list", 0);
				for (PlanElement pe : planElements) {
					rc.startGroup();
					rc.startField("element", 0);
					rc.startGroup();
					if (pe instanceof Activity a) {
						activity(a);
					} else if (pe instanceof Leg lg) {
						leg(lg);
					}
					rc.endGroup();
					rc.endField("element", 0);
					rc.endGroup();
				}
				rc.endField("list", 0);
			}
			rc.endGroup();
			rc.endField("elements", index);
		}

		/** Leg fields stay absent -- the list holds both kinds, as the XML holds both element types. */
		private void activity(Activity a) {
			required("kind", 0, "activity");
			optional("actType", 1, a.getType());
			optional("link", 2, linkId(a.getLinkId()));
			optional("startTime", 3, time(a.getStartTime()));
			optional("endTime", 4, time(a.getEndTime()));
			optional("maxDuration", 5, time(a.getMaximumDuration()));
			map("attributes", 10, attributes(a));
		}

		private void leg(Leg lg) {
			required("kind", 0, "leg");
			optional("mode", 6, lg.getMode());
			optional("routingMode", 7, lg.getRoutingMode()); // dedicated field, NOT in getAttributes()
			optional("travTime", 8, time(lg.getTravelTime()));
			route(9, lg.getRoute());
			map("attributes", 10, attributes(lg));
		}

		/** Absent for an unrouted leg; otherwise present for teleported and network routes alike. */
		private void route(int index, Route r) {
			if (r == null) {
				return;
			}
			rc.startField("route", index);
			rc.startGroup();
			optional("routeType", 0, r.getRouteType());
			double d = r.getDistance();
			optional("distance", 1, Double.isNaN(d) ? null : d); // MATSim uses NaN for undefined
			optional("travTime", 2, time(r.getTravelTime()));
			optional("startLink", 3, linkId(r.getStartLinkId()));
			optional("endLink", 4, linkId(r.getEndLinkId()));
			links(5, r);
			rc.endGroup();
			rc.endField("route", index);
		}

		/** The ordered link ids of a NetworkRoute; an empty (but present) list for a teleported leg. */
		private void links(int index, Route r) {
			rc.startField("links", index);
			rc.startGroup();
			if (r instanceof NetworkRoute nr) {
				rc.startField("list", 0);
				link(nr.getStartLinkId());
				for (Id<Link> l : nr.getLinkIds()) {
					link(l);
				}
				link(nr.getEndLinkId());
				rc.endField("list", 0);
			}
			rc.endGroup();
			rc.endField("links", index);
		}

		private void link(Id<Link> id) {
			rc.startGroup();
			required("element", 0, linkId(id));
			rc.endGroup();
		}

		/** {@code optional group <name> (MAP) { repeated group key_value { required key; required value } }} */
		private void map(String name, int index, Map<String, String> entries) {
			if (entries == null || entries.isEmpty()) {
				return; // absent, not empty
			}
			rc.startField(name, index);
			rc.startGroup();
			rc.startField("key_value", 0);
			for (Map.Entry<String, String> e : entries.entrySet()) {
				rc.startGroup();
				required("key", 0, e.getKey());
				required("value", 1, e.getValue());
				rc.endGroup();
			}
			rc.endField("key_value", 0);
			rc.endGroup();
			rc.endField(name, index);
		}

		private void required(String name, int index, String value) {
			rc.startField(name, index);
			rc.addBinary(Binary.fromString(value));
			rc.endField(name, index);
		}

		/** An absent optional field is simply never started. */
		private void optional(String name, int index, String value) {
			if (value != null) {
				required(name, index, value);
			}
		}

		private void optional(String name, int index, Double value) {
			if (value != null) {
				rc.startField(name, index);
				rc.addDouble(value);
				rc.endField(name, index);
			}
		}
	}

	/** An entity's generic MATSim attributes as name -&gt; string; null (not an empty map) if none. */
	private static Map<String, String> attributes(Attributable entity) {
		Map<String, Object> attrs = entity.getAttributes().getAsMap();
		if (attrs.isEmpty()) {
			return null;
		}
		Map<String, String> out = new LinkedHashMap<>();
		for (Map.Entry<String, Object> e : attrs.entrySet()) {
			out.put(e.getKey(), attributeValue(e.getValue()));
		}
		return out;
	}

	/**
	 * Renders an attribute value the way the plans XML writer does -- via MATSim's registered
	 * AttributeConverters -- so e.g. a {@code PersonVehicles} becomes {@code {"car":"1234_car",...}}
	 * instead of {@code String.valueOf}'s useless {@code PersonVehicles@6d303498} identity hash,
	 * which was not only unreadable but changed from run to run.
	 *
	 * Types with no registered converter (convertToString returns null and logs) fall back to
	 * String.valueOf. That is asked at most ONCE per class: the converter logs a warning on every
	 * miss, and this runs per attribute per plan, so an uncached miss would mean hundreds of
	 * thousands of identical warnings.
	 */
	private static String attributeValue(Object value) {
		if (value == null) {
			return null;
		}
		Class<?> type = value.getClass();
		if (CONVERTIBLE.getOrDefault(type, Boolean.TRUE)) {
			String converted;
			synchronized (CONVERTER) { // MATSim's converter makes no thread-safety promise
				converted = CONVERTER.convertToString(value);
			}
			if (converted != null) {
				CONVERTIBLE.putIfAbsent(type, Boolean.TRUE);
				return converted;
			}
			CONVERTIBLE.put(type, Boolean.FALSE); // remember the miss, so we neither retry nor re-log
		}
		return String.valueOf(value);
	}

	private static Double time(OptionalTime t) {
		return t.isDefined() ? t.seconds() : null;
	}

	private static String linkId(Id<Link> linkId) {
		return linkId != null ? linkId.toString() : null;
	}
}
