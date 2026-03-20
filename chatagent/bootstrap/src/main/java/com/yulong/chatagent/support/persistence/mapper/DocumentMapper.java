package com.yulong.chatagent.support.persistence.mapper;

import com.yulong.chatagent.support.persistence.entity.Document;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * @author charon
 * @description 针对表【document】的数据库操作Mapper
 * @createDate 2025-12-02 15:42:18
 * @Entity com.yulong.chatagent.support.persistence.entity.Document
 */
@Mapper
public interface DocumentMapper {
    int insert(Document document);

    Document selectById(String id);

    List<Document> selectByUserId(String userId);

    List<Document> selectByKbId(String kbId);

    List<Document> selectByKbIdAndUserId(@Param("kbId") String kbId, @Param("userId") String userId);

    int deleteById(String id);

    int updateById(Document document);
}
