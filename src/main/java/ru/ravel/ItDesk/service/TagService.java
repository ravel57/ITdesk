package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.model.Tag;
import ru.ravel.ItDesk.repository.TagRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TagService {

	private final TagRepository tagRepository;

	public List<Tag> getTags() {
		return tagRepository.findAll();
	}

	public Tag newTag(Tag tag) {
		return tagRepository.save(tag);
	}
}
