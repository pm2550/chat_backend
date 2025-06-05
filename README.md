# 聊天应用后端服务

这是一个基于Spring Boot的现代化聊天应用后端服务，支持实时消息传输、用户认证、聊天室管理等功能。

## 技术栈

- **Java 17** - 编程语言
- **Spring Boot 3.2.1** - 应用框架
- **Spring Security** - 安全认证
- **Spring Data JPA** - 数据访问层
- **Spring WebSocket** - 实时通信
- **MySQL 8.0** - 主数据库
- **H2 Database** - 测试数据库
- **JWT** - 身份认证令牌
- **Maven** - 项目构建工具
- **Lombok** - 代码简化
- **ModelMapper** - 对象映射

## 主要功能

### 用户管理
- 用户注册/登录/登出
- JWT令牌认证
- 用户信息管理
- 在线状态管理
- 头像上传

### 聊天功能
- 私聊/群聊/频道
- 实时消息传输（WebSocket）
- 多媒体消息支持（文本/图片/文件/语音/视频）
- 消息状态追踪（已发送/已送达/已读）
- 消息搜索

### 聊天室管理
- 创建/加入/退出聊天室
- 聊天室成员管理
- 权限控制（群主/管理员/普通成员）
- 聊天室设置

## 项目结构

```
src/main/java/com/chatapp/
├── config/              # 配置类
│   ├── AppConfig.java
│   ├── SecurityConfig.java
│   └── WebSocketConfig.java
├── controller/          # 控制器层
│   └── AuthController.java
├── dto/                 # 数据传输对象
│   ├── ApiResponse.java
│   ├── MessageDto.java
│   └── UserDto.java
├── entity/              # 实体类
│   ├── ChatRoom.java
│   ├── ChatRoomMember.java
│   ├── Message.java
│   └── User.java
├── repository/          # 数据访问层
│   ├── ChatRoomRepository.java
│   ├── MessageRepository.java
│   └── UserRepository.java
├── security/            # 安全相关
│   └── JwtAuthenticationFilter.java
├── service/             # 服务层
│   └── UserService.java
├── util/                # 工具类
│   └── JwtUtils.java
└── ChatAppApplication.java
```

## 安装和运行

### 环境要求
- Java 17+
- Maven 3.6+
- MySQL 8.0+（生产环境）

### 1. 克隆项目
```bash
git clone <repository-url>
cd chat_app_backend
```

### 2. 配置数据库
修改 `src/main/resources/application.yml` 中的数据库配置：
```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/chatapp?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=UTC
    username: your_username
    password: your_password
```

### 3. 安装依赖
```bash
mvn clean install
```

### 4. 运行应用
```bash
mvn spring-boot:run
```

应用将在 `http://localhost:8080/api` 启动。

## API 文档

### 认证接口

#### 用户注册
```http
POST /api/auth/register
Content-Type: application/json

{
  "username": "testuser",
  "password": "password123",
  "email": "test@example.com",
  "phone": "13800138000",
  "displayName": "测试用户"
}
```

#### 用户登录
```http
POST /api/auth/login
Content-Type: application/json

{
  "username": "testuser",
  "password": "password123"
}
```

#### 用户登出
```http
POST /api/auth/logout
Authorization: Bearer <jwt_token>
```

### WebSocket 连接

连接地址：`ws://localhost:8080/api/ws`

支持的消息类型：
- `/app/chat.sendMessage` - 发送消息
- `/app/chat.addUser` - 用户加入
- `/topic/public` - 公共频道
- `/topic/chat/{chatRoomId}` - 聊天室频道

## 配置说明

### JWT 配置
```yaml
jwt:
  secret: Y2hhdEFwcFNlY3JldEtleUZvckpXVFRva2VuU2lnbmluZw==  # Base64编码的密钥
  expiration: 86400000  # 24小时（毫秒）
```

### 文件上传配置
```yaml
file:
  upload:
    path: ./uploads/
    avatar-path: ./uploads/avatars/
    message-path: ./uploads/messages/
```

### WebSocket 配置
```yaml
websocket:
  allowed-origins: "*"
  endpoint: /ws
```

## 开发指南

### 添加新的API接口
1. 在相应的Controller中添加方法
2. 使用`@RequestMapping`注解定义路径
3. 使用`ApiResponse`包装返回结果
4. 添加适当的安全注解

### 数据库迁移
使用JPA的`ddl-auto: update`进行自动迁移，生产环境建议使用Flyway或Liquibase。

### 安全考虑
- 所有API（除认证相关）都需要JWT令牌
- 密码使用BCrypt加密
- 支持CORS跨域访问
- WebSocket连接支持安全验证

## 部署

### Docker部署
```dockerfile
FROM openjdk:17-jdk-slim
COPY target/chat-app-backend-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app.jar"]
```

### 生产环境配置
- 修改数据库连接信息
- 设置安全的JWT密钥
- 配置文件上传路径
- 启用HTTPS
- 配置日志输出

## 贡献指南

1. Fork 项目
2. 创建功能分支
3. 提交变更
4. 推送到分支
5. 创建 Pull Request

## 许可证

MIT License

## 联系方式

如有问题或建议，请通过以下方式联系：
- 邮箱：[your-email@example.com]
- GitHub Issues：[项目Issues页面] 