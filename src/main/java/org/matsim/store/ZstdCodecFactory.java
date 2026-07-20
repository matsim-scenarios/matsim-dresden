package org.matsim.store;

import com.github.luben.zstd.Zstd;
import org.apache.parquet.bytes.BytesInput;
import org.apache.parquet.compression.CompressionCodecFactory;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Minimal zstd codec factory for parquet-mr, backed directly by zstd-jni.
 *
 * parquet-mr's own {@code CodecFactory} resolves every codec through a Hadoop
 * {@code CompressionCodec}, which means instantiating {@code org.apache.hadoop.conf.Configuration}
 * and hence needing the whole Hadoop runtime on the classpath. This does the same job in a few
 * lines, so {@link PlanSnapshotWriter} keeps compressing with zstd while staying Hadoop-free.
 *
 * Only ZSTD (and the trivial UNCOMPRESSED) is supported; anything else is rejected rather than
 * silently written uncompressed.
 */
final class ZstdCodecFactory implements CompressionCodecFactory {

	/**
	 * Well above parquet-mr's default of 3, because this data compresses unusually well and the
	 * writer has time to spare. Measured on the 1pct Dresden output plans (levels 3/6/9/12/15/19):
	 * 10.9 / 9.5 / 9.1 / 8.9 / 8.7 / 8.0 MB, at 6.0 / 6.2 / 6.3 / 6.8 / 14.8 / 16.3 s end to end.
	 * 12 is the knee — 18% smaller than level 3 for under a second, whereas 15 doubles the time.
	 */
	private static final int LEVEL = 12;

	@Override
	public BytesInputCompressor getCompressor(CompressionCodecName codecName) {
		check(codecName);
		return new Compressor(codecName);
	}

	@Override
	public BytesInputDecompressor getDecompressor(CompressionCodecName codecName) {
		check(codecName);
		return new Decompressor(codecName);
	}

	@Override
	public void release() {
	}

	private static void check(CompressionCodecName codecName) {
		if (codecName != CompressionCodecName.ZSTD && codecName != CompressionCodecName.UNCOMPRESSED) {
			throw new UnsupportedOperationException("ZstdCodecFactory only handles ZSTD and UNCOMPRESSED, not " + codecName);
		}
	}

	private record Compressor(CompressionCodecName codecName) implements BytesInputCompressor {
		@Override
		public BytesInput compress(BytesInput bytes) throws IOException {
			if (codecName == CompressionCodecName.UNCOMPRESSED) {
				return BytesInput.copy(bytes);
			}
			return BytesInput.from(Zstd.compress(bytes.toByteArray(), LEVEL));
		}

		@Override
		public CompressionCodecName getCodecName() {
			return codecName;
		}

		@Override
		public void release() {
		}
	}

	private record Decompressor(CompressionCodecName codecName) implements BytesInputDecompressor {
		@Override
		public BytesInput decompress(BytesInput bytes, int decompressedSize) throws IOException {
			if (codecName == CompressionCodecName.UNCOMPRESSED) {
				return BytesInput.copy(bytes);
			}
			return BytesInput.from(Zstd.decompress(bytes.toByteArray(), decompressedSize));
		}

		@Override
		public void decompress(ByteBuffer input, int compressedSize, ByteBuffer output, int decompressedSize) throws IOException {
			if (codecName == CompressionCodecName.UNCOMPRESSED) {
				output.put(input);
				return;
			}
			Zstd.decompress(output, input);
		}

		@Override
		public void release() {
		}
	}
}
