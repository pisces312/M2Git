/*
 * 从上游 M2Git 补丁版 jgit jar（org.eclipse.jgit-6.10.1 patched build）反编译重建，
 * 逐字节码核对过行为。用于替代 java.util.zip.Inflater，规避 Android libcore
 * 与 JVM 的 Inflater 行为差异（M2Git issue #33 "inflater has been closed"）。
 */
package org.eclipse.jgit.compatible;

import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Android-compatible Inflater.
 */
public class InflaterCompat extends Inflater {

	private static final byte[] oneByteArray = new byte[1];

	public InflaterCompat(boolean nowrap) {
		super(nowrap);
	}

	@Override
	public int inflate(byte[] b, int off, int len) throws DataFormatException {
		if (len != 0) {
			return super.inflate(b, off, len);
		}
		// Android's Inflater does not behave like the JVM for a zero-length request;
		// probe with a 1-byte buffer and fail loudly if any data would be produced.
		int n = super.inflate(oneByteArray, 0, 1);
		if (n > 0) {
			throw new RuntimeException(
					"The Android Inflater Compat has served you ill, we were not supposed to read any data...");
		}
		return 0;
	}

	@Override
	public void end() {
		// No-op: Android throws IllegalStateException("inflater has been closed") when a
		// closed inflater is reused, and JGit's InflaterCache can reuse one. Native
		// resources are freed by the GC instead.
	}
}
