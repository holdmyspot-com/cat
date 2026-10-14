# {{service}} API

## GET /items

Returns every item. Accepts `limit`, a whole number from 1 to 500, and `cursor`, the value returned by the previous
page. Responds with `200` and a JSON object with `items` and `next_cursor`.

## POST /items

Creates an item from a JSON body with `name` and `quantity`. Responds with `201` and the created item, or `400` when
`quantity` is negative.

## GET /items/{id}

Returns one item. Responds with `404` when no item has that identifier.

## DELETE /items/{id}

Deletes one item. Responds with `204`, or `404` when no item has that identifier. Deleting an item that a pending order
references responds with `200`.

## PATCH /items/{id}

Changes an item's `name` or `quantity`. Responds with `200` and the changed item.
