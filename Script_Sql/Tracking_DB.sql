CREATE DATABASE TrackingDB;
GO
USE TrackingDB;
GO


CREATE TABLE dbo.GPS_DeviceTracking (
    Oid UNIQUEIDENTIFIER NOT NULL PRIMARY KEY DEFAULT NEWID(), -- Khóa chính dạng GUID
    DeviceID NVARCHAR(1000) NOT NULL,        -- Mã thiết bị
    Title NVARCHAR(100) NULL,                -- Tên hiển thị của thiết bị hoặc người dùng
    Latitude FLOAT NOT NULL,                 -- Vĩ độ
    Longitude FLOAT NOT NULL,                -- Kinh độ
    RecordDate DATETIME NOT NULL,            -- Thời gian ghi nhận tọa độ
    OptimisticLockField INT NULL,            -- Dùng cho cơ chế khóa lạc quan
    GCRecord INT NULL,                       -- Cột đánh dấu soft-delete
    UserName NVARCHAR(100) NULL              -- Tên người dùng (số điện thoại hoặc tài khoản)
);


select * from GPS_DeviceTracking

SELECT * FROM GPS_DeviceTracking ORDER BY RecordDate ASC;


truncate table GPS_DeviceTracking