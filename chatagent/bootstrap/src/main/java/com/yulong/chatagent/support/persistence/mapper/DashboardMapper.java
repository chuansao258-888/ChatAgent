package com.yulong.chatagent.support.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Read-only aggregation mapper for the admin dashboard.
 */
@Mapper
public interface DashboardMapper {

    Long countUsersBefore(@Param("end") LocalDateTime end);

    Long countActiveUsersBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    Long countSessionsBefore(@Param("end") LocalDateTime end);

    Long countSessionsBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    Long countMessagesBefore(@Param("end") LocalDateTime end);

    Long countMessagesBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    Map<String, Object> selectPerformanceAggregate(@Param("start") LocalDateTime start,
                                                   @Param("end") LocalDateTime end);

    List<Long> selectSuccessfulDurations(@Param("start") LocalDateTime start,
                                         @Param("end") LocalDateTime end);

    List<Map<String, Object>> selectSessionTrend(@Param("start") LocalDateTime start,
                                                 @Param("end") LocalDateTime end,
                                                 @Param("bucketUnit") String bucketUnit);

    List<Map<String, Object>> selectMessageTrend(@Param("start") LocalDateTime start,
                                                 @Param("end") LocalDateTime end,
                                                 @Param("bucketUnit") String bucketUnit);

    List<Map<String, Object>> selectActiveUserTrend(@Param("start") LocalDateTime start,
                                                    @Param("end") LocalDateTime end,
                                                    @Param("bucketUnit") String bucketUnit);

    List<Map<String, Object>> selectLatencyTrend(@Param("start") LocalDateTime start,
                                                 @Param("end") LocalDateTime end,
                                                 @Param("bucketUnit") String bucketUnit);

    List<Map<String, Object>> selectQualityTrend(@Param("start") LocalDateTime start,
                                                 @Param("end") LocalDateTime end,
                                                 @Param("bucketUnit") String bucketUnit);
}
