package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.model.Tag;
import ru.ravel.ItDesk.repository.TagRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TagService {

	private final TagRepository tagRepository;


	public List<Tag> getTags() {
		return tagRepository.findAll().stream().sorted().toList();
	}


	public Tag newTag(Tag tag) {
		tag.setOrderNumber(getTags().size() + 1);
		return tagRepository.save(tag);
	}


	public Tag updateTag(Tag tag) {
		return tagRepository.save(tag);
	}


	public void deleteTag(Long tagId) {
		tagRepository.deleteById(tagId);
	}


	public List<Tag> resortTags(@NotNull List<Tag> newOrderedTags) {
		List<Tag> tags = tagRepository.findAll();
		for (Tag tag : tags) {
			tag.setOrderNumber(newOrderedTags.indexOf(tag));
		}
		tagRepository.saveAll(tags);
		return tags.stream().sorted().toList();
	}
}
