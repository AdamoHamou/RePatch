-- The pipeline creates one schema per run (and the auto-create path in
-- DatabaseUtils needs CREATE); give the repatch user database-wide rights.
GRANT ALL PRIVILEGES ON *.* TO 'repatch'@'%';
FLUSH PRIVILEGES;
