CREATE TABLE IF NOT EXISTS database_mutation_locks (id BIGINT PRIMARY KEY);
INSERT INTO database_mutation_locks (id) SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM database_mutation_locks WHERE id = 1);
INSERT INTO database_mutation_locks (id) SELECT 2 WHERE NOT EXISTS (SELECT 1 FROM database_mutation_locks WHERE id = 2);
