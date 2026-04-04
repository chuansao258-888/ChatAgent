package com.yulong.chatagent.rag.parser;

import java.io.InputStream;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Supplier;

/**
 * Pluggable visual-document-parsing engine abstraction.
 */
public interface VdpEngine {

    default List<VdpPageResult> parsePages(Supplier<InputStream> pdfStream,
                                           List<Integer> pageIndices,
                                           VdpOptions options) {
        throw new UnsupportedOperationException("parsePages is not supported by this engine");
    }

    default VdpPageResult parsePage(Supplier<InputStream> imageStream,
                                    String imageFormat,
                                    VdpOptions options) {
        throw new UnsupportedOperationException("parsePage is not supported by this engine");
    }

    default String engineId() {
        return "default";
    }

    default String promptVersion() {
        return "default";
    }

    EnumSet<VdpMode> supportedModes();
}
