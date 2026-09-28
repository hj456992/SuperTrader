"""Exercise live localhost boundaries without generating model content."""
import json
from urllib.request import Request,build_opener,ProxyHandler
from urllib.error import HTTPError
base='http://127.0.0.1:48760'
opener=build_opener(ProxyHandler({}))
def call(path,body=None,headers=None):
    request=Request(base+path,data=body,headers=headers or {})
    try:
        with opener.open(request,timeout=15) as response:return response.status,json.load(response)
    except HTTPError as error:return error.code,json.load(error)
code,state=call('/api/state');assert code==200
headers={'X-Lab-Token':state['csrf'],'Content-Type':'application/json'}
selection=state['experts'][0]['versions'][0]['selections'][0]
payload=json.dumps({'name':'边界测试','duty':'仅验证接口','selections':[selection,selection]}).encode()
assert call('/api/experts',payload,headers)[0]==400
print('PASS HTTP duplicate document version selection rejected')
assert call('/api/experts',payload,{'Content-Type':'application/json'})[0]==403
assert call('/api/experts',payload,{**headers,'Origin':'https://outside.example'})[0]==403
assert call('/api/state',headers={'Host':'outside.example'})[0]==403
print('PASS CSRF Origin Host boundaries')
assert call('/api/documents?title=test',b'not-pdf',headers)[0]==400
assert call('/api/passage?id=unknown')[0]==400
print('PASS invalid PDF and unknown passage rejected')
assert call('/api/state')[1]==state
print('PASS rejected requests did not mutate state')
