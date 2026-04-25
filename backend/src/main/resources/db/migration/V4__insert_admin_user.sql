-- Create an admin user (password: admin123)
-- BCrypt hash of "admin123"
INSERT INTO users (email, password_hash, role, status, full_name)
VALUES (
    'admin@appvault.online',
    '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iAt6Z5EH',
    'ADMIN',
    'ACTIVE',
    'Platform Admin'
) ON CONFLICT (email) DO NOTHING;
