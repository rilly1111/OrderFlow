package com.itheima.orderflow.order;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    @Select("""
            SELECT id, version, acceptance_deadline_at
            FROM orders
            WHERE status = 'PAID' AND acceptance_deadline_at IS NOT NULL AND acceptance_deadline_at <= #{now}
            ORDER BY acceptance_deadline_at, id
            LIMIT #{limit}
            """)
    List<Order> findOverdueCandidates(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
