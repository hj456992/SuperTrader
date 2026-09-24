import test from 'node:test';
import assert from 'node:assert/strict';
import {acknowledgeSentDraft} from './draft-state.js';
test('a reply save cannot erase input typed while the request was in flight',()=>{
  const drafts=new Map([['chat','新输入的草稿'],['other','另一个会话']]);
  assert.equal(acknowledgeSentDraft(drafts,'chat','刚发出的内容'),false);
  assert.equal(drafts.get('chat'),'新输入的草稿');
  assert.equal(drafts.get('other'),'另一个会话');
});
test('the unchanged sent draft is cleared after save',()=>{
  const drafts=new Map([['chat','刚发出的内容']]);
  assert.equal(acknowledgeSentDraft(drafts,'chat','刚发出的内容'),true);
  assert.equal(drafts.get('chat'),'');
});
