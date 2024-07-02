package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.model.ItDeskInstance;

@Repository
public interface ItDeskInstanceRepository extends JpaRepository<ItDeskInstance, Long> {

}
