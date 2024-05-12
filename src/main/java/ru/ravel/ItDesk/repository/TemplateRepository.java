package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.model.Template;

@Repository
public interface TemplateRepository extends JpaRepository<Template, Long> {
}
