// Local visual fixture only. Imported exclusively for ?uiPreview=1 on localhost.
export const previewCategories = [
  { id: 1, name: '教材' }, { id: 2, name: '数码与电器' },
  { id: 3, name: '宿舍好物' }, { id: 4, name: '校园代步' },
  { id: 5, name: '生活用品' }, { id: 6, name: '其他校园闲置' }
];

const examples = [
  ['微积分教材三册，书页完整', 1, 36, 'GOOD'],
  ['13 英寸轻薄笔记本电脑', 2, 1599, 'GOOD'],
  ['奶油色可调节书桌台灯', 3, 42, 'LIKE_NEW'],
  ['白色头戴式蓝牙耳机', 2, 168, 'GOOD'],
  ['宿舍用小容量电热水壶', 3, 55, 'GOOD'],
  ['绿色通勤自行车', 4, 320, 'FAIR'],
  ['棉麻帆布包，可装课本', 5, 18, 'LIKE_NEW'],
  ['黑色机械键盘，轴体正常', 2, 129, 'GOOD'],
  ['浅蓝色双肩书包', 5, 46, 'GOOD'],
  ['宿舍折叠椅，收纳方便', 3, 65, 'GOOD'],
  ['入门数码相机，含电池', 2, 699, 'FAIR'],
  ['不锈钢保温杯', 6, 25, 'LIKE_NEW']
];

export const previewItems = examples.map(([title, categoryId, price, condition], index) => ({
  id: `preview-${index + 1}`, title, categoryId,
  categoryName: previewCategories.find(category => category.id === categoryId).name,
  price, condition, sellerNickname: `预览用户 ${String(index + 1).padStart(2, '0')}`,
  previewTile: index
}));
