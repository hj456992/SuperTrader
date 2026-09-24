import test from 'node:test';
import assert from 'node:assert/strict';
import { requestPayload, mutationOwnsContext } from './ranch-ui.js';
test('an open form keeps its captured revision after polling advances state',()=>{
 const form={revision:3,name:'older form value'};
 assert.deepEqual(requestPayload(form,4),{revision:3,name:'older form value'});
 assert.deepEqual(requestPayload({name:'immediate action'},4),{revision:4,name:'immediate action'});
 assert.equal(requestPayload({revision:0,name:'initial form'},5).revision,0);
});
test('a slow mutation cannot own a dialog or page opened after it started',()=>{
 const origin={dialogEpoch:1,page:'person',selected:'a',tab:'profile'};
 assert.equal(mutationOwnsContext(origin,{...origin}),true);
 assert.equal(mutationOwnsContext(origin,{...origin,dialogEpoch:2}),false);
 assert.equal(mutationOwnsContext(origin,{...origin,selected:'b'}),false);
 assert.equal(mutationOwnsContext(origin,{...origin,page:'library'}),false);
 assert.equal(mutationOwnsContext(origin,{...origin,tab:'materials'}),false);
});
