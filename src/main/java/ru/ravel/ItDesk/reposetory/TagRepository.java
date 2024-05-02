package ru.ravel.ItDesk.reposetory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.model.Tag;

@Repository
public interface TagRepository extends JpaRepository<Tag, Long> {
}
