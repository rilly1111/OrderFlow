package com.itheima.orderflow.auth;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AccountMapper {

    @Select("""
            SELECT id, password_hash, role
            FROM accounts
            WHERE username = #{username}
            """)
    LoginAccount findByUsername(String username);

    record LoginAccount(long id, String passwordHash, String role) {
        @Override
        public String toString() {
            return "LoginAccount[id=" + id + ", role=" + role + "]";
        }
    }
}
