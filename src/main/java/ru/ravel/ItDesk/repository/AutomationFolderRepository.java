package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.ravel.ItDesk.model.AutomationFolder;


public interface AutomationFolderRepository extends JpaRepository<AutomationFolder, Long> {

	@Query("select min(f.displayOrder) from AutomationFolder f where f.parentId is null")
	Integer minDisplayOrder();

	@Query("select max(f.displayOrder) from AutomationFolder f where f.parentId is null")
	Integer maxDisplayOrder();
}
