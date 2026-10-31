-- Only a superuser can do this. The platform migration creates every other role.
CREATE ROLE idp_owner LOGIN CREATEROLE PASSWORD 'idp_owner';
ALTER DATABASE idp OWNER TO idp_owner;
-- `provisioner` joins idp_owner to run DDL, and a role cannot grant itself.
CREATE ROLE provisioner LOGIN NOINHERIT IN ROLE idp_owner;
