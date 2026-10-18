-- Mirrors docker/init/01-app-role.sql: the application role exists before the first migration.
CREATE ROLE lms_app LOGIN PASSWORD 'lms_app';
