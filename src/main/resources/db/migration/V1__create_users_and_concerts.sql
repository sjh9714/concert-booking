CREATE TABLE users (
    id          BIGSERIAL PRIMARY KEY,
    email       VARCHAR(255) NOT NULL UNIQUE,
    password    VARCHAR(255) NOT NULL,
    nickname    VARCHAR(100) NOT NULL,
    role        VARCHAR(20) NOT NULL DEFAULT 'USER',
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE concerts (
    id          BIGSERIAL PRIMARY KEY,
    title       VARCHAR(255) NOT NULL,
    description TEXT,
    venue       VARCHAR(255) NOT NULL,
    artist      VARCHAR(255) NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE concert_schedules (
    id              BIGSERIAL PRIMARY KEY,
    concert_id      BIGINT NOT NULL REFERENCES concerts(id),
    schedule_date   DATE NOT NULL,
    start_time      TIME NOT NULL,
    total_seats     INT NOT NULL,
    available_seats INT NOT NULL,
    version         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);
