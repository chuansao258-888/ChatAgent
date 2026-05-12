package com.yulong.chatagent.rag.parser;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Registry that resolves parser implementations either by logical type or MIME type.
 */
@Component
public class DocumentParserSelector {

    private final List<DocumentParser> strategies;
    private final Map<String, DocumentParser> strategyMap;
    private final DocumentParser fallbackParser;
    private final FileTypeDetector fileTypeDetector;

    public DocumentParserSelector(List<DocumentParser> parsers, FileTypeDetector fileTypeDetector) {
        this.strategies = parsers;
        this.fileTypeDetector = fileTypeDetector;
        this.strategyMap = parsers.stream()
                .collect(Collectors.toMap(
                        DocumentParser::getParserType,
                        Function.identity(),
                        (existing, replacement) -> existing
                ));
        this.fallbackParser = strategyMap.get(ParserType.TIKA.getType());
    }

    /**
     * Resolves a parser by the configured parser type identifier.
     */
    public DocumentParser select(String parserType) {
        return strategyMap.get(parserType);
    }

    public DocumentParser selectParser(byte[] prefix, String originalFilename, String mimeType) {
        return selectParser(prefix, originalFilename, mimeType, PipelineSource.KNOWLEDGE);
    }

    public DocumentParser selectParser(byte[] prefix,
                                       String originalFilename,
                                       String mimeType,
                                       PipelineSource pipelineSource) {
        DetectedFileType detectedFileType = fileTypeDetector.detect(prefix, originalFilename, mimeType, pipelineSource);
        if (detectedFileType.rejected()) {
            throw new FileRejectedException(detectedFileType.rejectionReason());
        }
        return strategies.stream()
                .filter(parser -> parser.supports(detectedFileType))
                .sorted(java.util.Comparator.comparingInt(DocumentParser::getSelectionPriority))
                .findFirst()
                .orElse(fallbackParser);
    }

    // 旧诊断入口已停用：当前生产路径只通过 select/selectParser 解析具体文件，
    // 没有管理端或健康检查读取 parser 策略列表。
    // public List<DocumentParser> getAllStrategies() {
    //     return List.copyOf(strategies);
    // }

    // public List<String> getAvailableTypes() {
    //     return strategies.stream()
    //             .map(DocumentParser::getParserType)
    //             .toList();
    // }
}
