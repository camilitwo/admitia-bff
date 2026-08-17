-- Las salas ahora pueden crearse con capacidad desde 0 en adelante (antes exigía >= 3).
ALTER TABLE prekinder_rooms
    DROP CONSTRAINT prekinder_rooms_capacity_check;

ALTER TABLE prekinder_rooms
    ADD CONSTRAINT prekinder_rooms_capacity_check CHECK (capacity >= 0);
