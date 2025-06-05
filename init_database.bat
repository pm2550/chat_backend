@echo off
chcp 65001 >nul

REM 添加MySQL到PATH
set PATH=%PATH%;C:\Program Files\MySQL\MySQL Server 8.0\bin

echo ========================================
echo 聊天应用数据库初始化脚本
echo ========================================
echo.

set MYSQL_USER=root
set MYSQL_PASSWORD=pimao1011
set DATABASE_NAME=chatapp

echo 正在检查MySQL连接...
mysql -u%MYSQL_USER% -p%MYSQL_PASSWORD% -e "SELECT VERSION();" >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Cannot connect to MySQL server
    echo Please ensure:
    echo 1. MySQL server is running
    echo 2. Username/password is correct: %MYSQL_USER%/%MYSQL_PASSWORD%
    echo 3. MySQL client is installed and in PATH
    pause
    exit /b 1
)

echo [成功] MySQL连接正常
echo.

echo 正在创建数据库和表结构...
mysql -u%MYSQL_USER% -p%MYSQL_PASSWORD% < "%~dp0src\main\resources\sql\create_database.sql"
if errorlevel 1 (
    echo [错误] 数据库创建失败
    pause
    exit /b 1
)

echo [成功] 数据库和表结构创建完成
echo.

echo 是否要插入测试数据？(y/n)
set /p choice=
if /i "%choice%"=="y" (
    echo 正在插入测试数据...
    mysql -u%MYSQL_USER% -p%MYSQL_PASSWORD% < "%~dp0src\main\resources\sql\insert_test_data.sql"
    if errorlevel 1 (
        echo [警告] 测试数据插入失败，但数据库已创建成功
    ) else (
        echo [成功] 测试数据插入完成
    )
)

echo.
echo ========================================
echo 数据库初始化完成！
echo ========================================
echo.
echo 数据库信息：
echo - 数据库名: %DATABASE_NAME%
echo - 用户名: %MYSQL_USER%
echo - 密码: %MYSQL_PASSWORD%
echo.
echo 默认管理员账户：
echo - 用户名: admin
echo - 密码: admin123
echo.
echo 测试用户账户（如果插入了测试数据）：
echo - 用户名: zhangsan, lisi, wangwu, zhaoliu
echo - 密码: 123456
echo.
echo 现在可以启动后端应用了！
pause
