import { conditionName, el, emptyPhoto, money } from './core.js';

// Real listings and the isolated UI preview share one card layout.
export function createMarketCard(item, { onOpen, favoriteButton, preview = false }) {
  const card = el('article', '', 'market-item product-card');
  const open = el('button', '', 'market-item-link product-open');
  open.type = 'button';
  open.setAttribute('aria-label', preview
    ? `预览商品：${item.title}，${money(item.price)}`
    : `查看${item.title}，${money(item.price)}`);

  const photo = el('div', '', 'market-item-photo');
  if (preview && Number.isInteger(item.previewTile)) {
    const tile = el('div', '', 'market-preview-photo');
    tile.style.setProperty('--tile-x', `${item.previewTile % 4 * 100 / 3}%`);
    tile.style.setProperty('--tile-y', `${Math.floor(item.previewTile / 4) * 50}%`);
    tile.setAttribute('role', 'img');
    tile.setAttribute('aria-label', item.title);
    photo.append(tile);
  } else if (item.coverImageUrl) {
    const image = document.createElement('img');
    image.src = item.coverImageUrl;
    image.alt = item.title || '商品照片';
    image.loading = 'lazy';
    image.decoding = 'async';
    image.addEventListener('error', () => photo.replaceChildren(emptyPhoto()), { once: true });
    photo.append(image);
  } else photo.append(emptyPhoto());

  const info = el('div', '', 'market-item-info');
  info.append(el('p', `${item.categoryName || '校园闲置'} · ${conditionName[item.condition] || item.condition}`, 'market-item-meta'),
    el('h3', item.title), el('strong', money(item.price), 'market-item-price'),
    el('p', item.sellerNickname || '校园同学', 'market-item-seller'));
  open.append(photo, info);
  open.addEventListener('click', onOpen);
  card.append(open);
  if (preview) {
    const heart = el('span', '♡', 'market-preview-heart');
    heart.setAttribute('aria-hidden', 'true');
    card.append(heart);
  } else card.append(favoriteButton(item.id, true));
  return card;
}
