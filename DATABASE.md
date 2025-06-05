# 数据库配置说明

## 概述
本聊天应用支持MySQL和H2数据库，默认配置为MySQL。本文档说明如何设置和初始化数据库。

## 数据库表结构

### 1. users - 用户表
| 字段名 | 类型 | 说明 |
|--------|------|------|
| id | BIGINT | 主键，自增 |
| username | VARCHAR(50) | 用户名，唯一 |
| password | VARCHAR(255) | 密码（加密） |
| email | VARCHAR(100) | 邮箱，唯一 |
| phone | VARCHAR(20) | 手机号 |
| display_name | VARCHAR(100) | 显示名称 |
| avatar_url | VARCHAR(500) | 头像URL |
| bio | TEXT | 个人简介 |
| online_status | ENUM | 在线状态：ONLINE/AWAY/BUSY/OFFLINE |
| last_seen | DATETIME | 最后在线时间 |
| is_active | BOOLEAN | 是否激活 |
| created_at | DATETIME | 创建时间 |
| updated_at | DATETIME | 更新时间 |

### 2. user_roles - 用户角色表
| 字段名 | 类型 | 说明 |
|--------|------|------|
| user_id | BIGINT | 用户ID，外键 |
| role | ENUM | 角色：USER/ADMIN/MODERATOR |

### 3. chat_rooms - 聊天室表
| 字段名 | 类型 | 说明 |
|--------|------|------|
| id | BIGINT | 主键，自增 |
| name | VARCHAR(100) | 聊天室名称 |
| description | TEXT | 聊天室描述 |
| room_type | ENUM | 类型：PRIVATE/GROUP/CHANNEL/PUBLIC |
| avatar_url | VARCHAR(500) | 聊天室头像URL |
| created_by | BIGINT | 创建者ID，外键 |
| is_active | BOOLEAN | 是否激活 |
| is_private | BOOLEAN | 是否私有 |
| max_members | INT | 最大成员数 |
| created_at | DATETIME | 创建时间 |
| updated_at | DATETIME | 更新时间 |

### 4. chat_room_members - 聊天室成员表
| 字段名 | 类型 | 说明 |
|--------|------|------|
| id | BIGINT | 主键，自增 |
| user_id | BIGINT | 用户ID，外键 |
| chat_room_id | BIGINT | 聊天室ID，外键 |
| member_role | ENUM | 成员角色：OWNER/ADMIN/MODERATOR/MEMBER |
| nickname | VARCHAR(100) | 群内昵称 |
| is_muted | BOOLEAN | 是否被禁言 |
| is_admin | BOOLEAN | 是否为管理员 |
| joined_at | DATETIME | 加入时间 |
| last_read_message_id | BIGINT | 最后阅读消息ID |
| unread_count | INT | 未读消息数量 |

### 5. messages - 消息表
| 字段名 | 类型 | 说明 |
|--------|------|------|
| id | BIGINT | 主键，自增 |
| content | TEXT | 消息内容 |
| message_type | ENUM | 消息类型：TEXT/IMAGE/FILE/VOICE/VIDEO/LOCATION/SYSTEM |
| sender_id | BIGINT | 发送者ID，外键 |
| chat_room_id | BIGINT | 聊天室ID，外键 |
| reply_to_message_id | BIGINT | 回复消息ID，外键 |
| file_url | VARCHAR(500) | 文件URL |
| file_name | VARCHAR(255) | 文件名 |
| file_size | BIGINT | 文件大小（字节） |
| file_type | VARCHAR(100) | 文件类型 |
| thumbnail_url | VARCHAR(500) | 缩略图URL |
| duration | INT | 音频/视频时长（秒） |
| width | INT | 图片/视频宽度 |
| height | INT | 图片/视频高度 |
| message_status | ENUM | 消息状态：SENDING/SENT/DELIVERED/READ/FAILED |
| is_deleted | BOOLEAN | 是否已删除 |
| is_edited | BOOLEAN | 是否已编辑 |
| created_at | DATETIME | 创建时间 |
| updated_at | DATETIME | 更新时间 |
| read_count | INT | 阅读数量 |

## 数据库配置

### MySQL配置（推荐生产环境）
```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/chatapp?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=UTC
    username: root
    password: root
    driver-class-name: com.mysql.cj.jdbc.Driver
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: true
    properties:
      hibernate:
        dialect: org.hibernate.dialect.MySQL8Dialect
```

### H2配置（开发/测试环境）
```yaml
spring:
  datasource:
    url: jdbc:h2:mem:chat_app
    username: sa
    password: 
    driver-class-name: org.h2.Driver
  h2:
    console:
      enabled: true
      path: /h2-console
  jpa:
    hibernate:
      ddl-auto: update
    properties:
      hibernate:
        dialect: org.hibernate.dialect.H2Dialect
```

## 初始化步骤

### 1. MySQL数据库初始化
1. 确保MySQL服务器运行
2. 执行SQL脚本创建数据库和表：
   ```bash
   mysql -u root -p < src/main/resources/sql/create_database.sql
   ```
3. （可选）插入测试数据：
   ```bash
   mysql -u root -p < src/main/resources/sql/insert_test_data.sql
   ```

### 2. H2数据库初始化
H2数据库在应用启动时会自动创建表结构（使用JPA的ddl-auto=update），无需手动执行SQL脚本。

## 默认用户

### 管理员用户
- 用户名：`admin`
- 密码：`admin123`
- 邮箱：`admin@chatapp.com`
- 角色：ADMIN, USER

### 测试用户（仅在执行测试数据脚本后存在）
- 用户名：`zhangsan`, `lisi`, `wangwu`, `zhaoliu`
- 密码：`123456`
- 角色：USER

## 注意事项

1. **密码加密**：所有密码都使用BCrypt加密存储
2. **字符集**：数据库和表使用utf8mb4字符集，支持emoji
3. **索引优化**：为常用查询字段创建了索引
4. **外键约束**：确保数据完整性
5. **软删除**：消息支持软删除（is_deleted字段）
6. **时间字段**：使用DATETIME类型，自动维护创建和更新时间

## 性能优化建议

1. **索引**：根据查询模式添加合适的索引
2. **分区**：消息表可按时间分区
3. **归档**：定期归档老旧消息
4. **缓存**：使用Redis缓存热点数据
5. **读写分离**：使用主从复制提高读性能

## 备份建议

1. **定期备份**：每日自动备份数据库
2. **增量备份**：使用二进制日志进行增量备份
3. **测试恢复**：定期测试备份恢复流程
