package csc210.algorithm.client;

/**
 * The {@code data} member of an {@link Envelope}, read straight into its final
 * type. The envelope's other fields are skipped by the mapper's
 * unknown-field tolerance.
 *
 * <p>Exists for speed, not shape. Reading into {@link Envelope} first holds a
 * response three times over — the text, a {@code JsonNode} tree of it, then the
 * records — and a DSTNS 2.0 topology is 20 MB of text. Streaming into this
 * builds the records directly from the socket.
 */
public record Payload<T>(T data) {}
