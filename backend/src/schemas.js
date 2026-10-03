const ingredient = { type: 'object', additionalProperties: false,
  properties: { id: { type: 'string' }, grams: { type: 'number', exclusiveMinimum: 0, maximum: 2000 } }, required: ['id', 'grams'] };
const step = { type: 'object', additionalProperties: false,
  properties: { action: { type: 'string', enum: ['cut', 'mash', 'mix', 'layer', 'spread', 'warm', 'soak'] },
    ingredients: { type: 'array', minItems: 1, maxItems: 7, items: { type: 'string' } } }, required: ['action', 'ingredients'] };
export const RECIPE_SCHEMA = { type: 'object', additionalProperties: false, properties: {
  recipes: { type: 'array', minItems: 1, maxItems: 3, items: { type: 'object', additionalProperties: false,
    properties: { title: { type: 'string' }, ingredients: { type: 'array', minItems: 2, maxItems: 7, items: ingredient },
      method: { type: 'string', enum: ['assemble', 'soak', 'warm'] }, minutes: { type: 'integer', minimum: 1, maximum: 90 },
      servings: { type: 'integer', minimum: 1, maximum: 2 }, preparation: { type: 'array', minItems: 2, maxItems: 6, items: step } },
    required: ['title', 'ingredients', 'method', 'minutes', 'servings', 'preparation'] } },
}, required: ['recipes'] };
export const PLAN_SCHEMA = { type: 'object', additionalProperties: false, properties: {
  days: { type: 'array', minItems: 1, maxItems: 7, items: { type: 'object', additionalProperties: false,
    properties: Object.fromEntries(['breakfast', 'lunch', 'dinner'].map(slot => [slot, { type: 'integer', minimum: 0, maximum: 19 }])),
    required: ['breakfast', 'lunch', 'dinner'] } },
}, required: ['days'] };
