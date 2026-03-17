package com.yulong.chatagent.support.persistence.adapter.knowledge;

import com.yulong.chatagent.knowledge.port.DocumentRepository;
import com.yulong.chatagent.support.persistence.converter.DocumentConverter;
import com.yulong.chatagent.support.persistence.entity.Document;
import com.yulong.chatagent.support.persistence.mapper.DocumentMapper;
import com.yulong.chatagent.support.dto.DocumentDTO;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Repository
public class MyBatisDocumentRepository implements DocumentRepository {

    private final DocumentMapper documentMapper;
    private final DocumentConverter documentConverter;

    public MyBatisDocumentRepository(DocumentMapper documentMapper,
                                     DocumentConverter documentConverter) {
        this.documentMapper = documentMapper;
        this.documentConverter = documentConverter;
    }

    @Override
    public List<DocumentDTO> findAll() {
        return toDTOList(documentMapper.selectAll());
    }

    @Override
    public DocumentDTO findById(String id) {
        return toDTO(documentMapper.selectById(id));
    }

    @Override
    public List<DocumentDTO> findByKnowledgeBaseId(String kbId) {
        return toDTOList(documentMapper.selectByKbId(kbId));
    }

    @Override
    public boolean save(DocumentDTO document) {
        Document entity = toEntity(document);
        boolean saved = documentMapper.insert(entity) > 0;
        if (saved) {
            document.setId(entity.getId());
        }
        return saved;
    }

    @Override
    public boolean update(DocumentDTO document) {
        return documentMapper.updateById(toEntity(document)) > 0;
    }

    @Override
    public boolean deleteById(String id) {
        return documentMapper.deleteById(id) > 0;
    }

    private List<DocumentDTO> toDTOList(List<Document> entities) {
        List<DocumentDTO> result = new ArrayList<>();
        for (Document entity : entities) {
            result.add(toDTO(entity));
        }
        return result;
    }

    private DocumentDTO toDTO(Document entity) {
        if (entity == null) {
            return null;
        }
        try {
            return documentConverter.toDTO(entity);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize document", e);
        }
    }

    private Document toEntity(DocumentDTO dto) {
        try {
            return documentConverter.toEntity(dto);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize document", e);
        }
    }
}
