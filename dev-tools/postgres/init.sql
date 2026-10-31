-- Owner of all schemas. Migrations of task 4 create every other role.
CREATE ROLE idp_owner LOGIN CREATEROLE PASSWORD 'idp_owner';
ALTER DATABASE idp OWNER TO idp_owner;
