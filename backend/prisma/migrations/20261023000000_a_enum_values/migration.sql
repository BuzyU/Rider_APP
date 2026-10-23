-- Migration: Add enum values before they are used in transactional migrations
-- Ensures Postgres 12-16 safety inside transactions

ALTER TYPE "InviteStatus" ADD VALUE IF NOT EXISTS 'LEFT';
