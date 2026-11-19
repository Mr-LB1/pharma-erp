-- V6：清洗 warehouse.temp_type 存量脏数据（DML 订正，非 DDL）
-- 历史数据误写中文温控类型，订正为码值：阴凉→COOL、冷藏→COLD、常温→ROOM_TEMP。
-- 已查询现存值仅有「阴凉」「冷藏」，故以下 UPDATE 按实际存在的值执行。

UPDATE warehouse SET temp_type = 'COOL' WHERE temp_type = '阴凉';
UPDATE warehouse SET temp_type = 'COLD' WHERE temp_type = '冷藏';
UPDATE warehouse SET temp_type = 'ROOM_TEMP' WHERE temp_type = '常温';
