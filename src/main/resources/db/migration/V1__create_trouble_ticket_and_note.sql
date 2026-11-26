-- Trouble tickets and their notes (README §4–§7).

-- Global sequences behind the TT-{year}-{000001} and NOTE-{000001} identifiers (README §7).
create sequence trouble_ticket_seq;
create sequence note_seq;

create table trouble_ticket (
    id          varchar(32)  primary key,
    tenant_id   varchar(255) not null,
    -- The spec sets no maxLength; longer values are rejected with 400 (README §8).
    external_id varchar(255) not null,
    service_id  bigint       not null,
    description text         not null,
    status      varchar(20)  not null,
    created_at  timestamptz  not null,
    updated_at  timestamptz  not null,
    -- Optimistic locking, e.g. a SOZ acknowledge racing a client close (README §3).
    version     bigint       not null,
    constraint ck_trouble_ticket_status
        check (status in ('NEW', 'ACKNOWLEDGED', 'IN_PROGRESS', 'RESOLVED', 'REJECTED', 'CLOSED')),
    -- Idempotency key of create (README §4).
    constraint uq_trouble_ticket_tenant_external unique (tenant_id, external_id)
);

-- Listing: all tickets of a tenant, oldest first (README §10).
create index ix_trouble_ticket_tenant_created on trouble_ticket (tenant_id, created_at, id);

create table note (
    id         varchar(32) primary key,
    ticket_id  varchar(32) not null references trouble_ticket (id),
    text       text        not null,
    created_at timestamptz not null
);

-- Notes of a ticket, oldest first (README §6). Also covers the foreign key.
create index ix_note_ticket_created on note (ticket_id, created_at, id);
