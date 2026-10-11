-- A new policy creates a new candidate, never rewrites historical evidence or carries approval over.
ALTER TABLE shelter.styled_quality_lessons
 ADD COLUMN revalidated_from_id uuid REFERENCES shelter.styled_quality_lessons(id) ON DELETE SET NULL;
CREATE INDEX styled_lesson_revalidation_parent ON shelter.styled_quality_lessons(revalidated_from_id)
 WHERE revalidated_from_id IS NOT NULL;
