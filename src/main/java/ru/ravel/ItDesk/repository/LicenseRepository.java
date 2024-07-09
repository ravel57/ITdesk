package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.model.License;

@Repository
public interface LicenseRepository extends JpaRepository<License, Long> {

}
