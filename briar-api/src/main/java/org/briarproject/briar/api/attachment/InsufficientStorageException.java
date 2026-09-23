package org.briarproject.briar.api.attachment;

import java.io.IOException;

/**
 * Thrown when storing a file would leave too little free space for the
 * database to keep working, so the file was not stored.
 */
public class InsufficientStorageException extends IOException {
}
