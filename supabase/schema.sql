create table if not exists preply_mail (
  message_id text primary key,
  student_full_name text,
  student_normalized text,
  amount numeric,
  currency text,
  received_at timestamptz,
  subject text,
  snippet text,
  kind text check (kind in ('booking','cancellation_compensation')),
  lesson_date date
);

create index if not exists idx_preply_mail_student_norm on preply_mail(student_normalized);
create index if not exists idx_preply_mail_kind_received on preply_mail(kind, received_at desc);


