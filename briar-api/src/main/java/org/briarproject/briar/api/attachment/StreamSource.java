package org.briarproject.briar.api.attachment;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;

/**
 * Supplies the contents of a file to be stored. The file is read twice when
 * it is stored: once to hash its chunks and once to store them. So the
 * source must be able to open a fresh stream each time it is asked.
 */
@NotNullByDefault
public interface StreamSource {

	InputStream openStream() throws IOException;
}
