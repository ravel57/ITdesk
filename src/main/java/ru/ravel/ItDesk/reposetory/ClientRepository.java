package ru.ravel.ItDesk.reposetory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.models.Client;

@Repository
public interface ClientRepository extends JpaRepository<Client, Long> {
}
