#!/bin/bash

# 聊天应用数据库初始化脚本 (Linux/Mac)
# 请确保MySQL服务器已启动

echo "========================================"
echo "聊天应用数据库初始化脚本"
echo "========================================"
echo

# 设置数据库连接信息
MYSQL_USER="root"
MYSQL_PASSWORD="pimao1011"
DATABASE_NAME="chatapp"

# 检查MySQL客户端是否可用
if ! command -v mysql &> /dev/null; then
    echo "[错误] 未找到MySQL客户端"
    echo "请安装MySQL客户端："
    echo "  Ubuntu/Debian: sudo apt-get install mysql-client"
    echo "  CentOS/RHEL: sudo yum install mysql"
    echo "  macOS: brew install mysql-client"
    exit 1
fi

echo "正在检查MySQL连接..."

# 测试MySQL连接
if ! mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" -e "SELECT VERSION();" &> /dev/null; then
    echo "[错误] 无法连接到MySQL服务器"
    echo "请确保："
    echo "1. MySQL服务器正在运行"
    echo "2. 用户名密码正确: $MYSQL_USER/$MYSQL_PASSWORD"
    echo "3. MySQL服务端已启动"
    exit 1
fi

echo "[成功] MySQL连接正常"
echo

echo "正在创建数据库和表结构..."
if ! mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" < "$(dirname "$0")/src/main/resources/sql/create_database.sql"; then
    echo "[错误] 数据库创建失败"
    exit 1
fi

echo "[成功] 数据库和表结构创建完成"
echo

# 询问是否插入测试数据
read -p "是否要插入测试数据？(y/n): " choice
case "$choice" in 
    y|Y|yes|YES )
        echo "正在插入测试数据..."
        if ! mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" < "$(dirname "$0")/src/main/resources/sql/insert_test_data.sql"; then
            echo "[警告] 测试数据插入失败，但数据库已创建成功"
        else
            echo "[成功] 测试数据插入完成"
        fi
        ;;
    * )
        echo "跳过测试数据插入"
        ;;
esac

echo
echo "========================================"
echo "数据库初始化完成！"
echo "========================================"
echo
echo "数据库信息："
echo "- 数据库名: $DATABASE_NAME"
echo "- 用户名: $MYSQL_USER"
echo "- 密码: $MYSQL_PASSWORD"
echo
echo "默认管理员账户："
echo "- 用户名: admin"
echo "- 密码: admin123"
echo
echo "测试用户账户（如果插入了测试数据）："
echo "- 用户名: zhangsan, lisi, wangwu, zhaoliu"
echo "- 密码: 123456"
echo
echo "现在可以启动后端应用了！"
echo "运行命令: mvn spring-boot:run"
