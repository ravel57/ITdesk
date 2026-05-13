UPDATE task_filter
SET selected_options = '[
  {
    "label": "Тег",
    "selectedOptions": [
      "SLA скоро нарушится",
      "SLA нарушен",
      "Просрочено"
    ]
  },
  {
    "label": "Статус",
    "selectedOptions": [
      "Новая",
      "Клиент не отвечает"
    ]
  },
  {
    "label": "Исполнитель",
    "selectedOptions": [
      "Вы",
      "Без исполнителя"
    ]
  }
]'::jsonb
WHERE label = 'Требуют внимания';


ALTER TABLE message
    ADD COLUMN IF NOT EXISTS edited_at TIMESTAMP WITH TIME ZONE;