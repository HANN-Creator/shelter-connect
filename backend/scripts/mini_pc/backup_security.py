"""Windows-only DPAPI helpers. Machine-scope blobs MUST stay in a restricted ACL directory."""
import ctypes,json,subprocess
from ctypes import wintypes
from pathlib import Path

class Blob(ctypes.Structure):
    _fields_=[('size',wintypes.DWORD),('data',ctypes.POINTER(ctypes.c_ubyte))]

def transform(data,decrypt=False):
    buffer=ctypes.create_string_buffer(data)
    source=Blob(len(data),ctypes.cast(buffer,ctypes.POINTER(ctypes.c_ubyte)));target=Blob()
    crypt=ctypes.WinDLL('crypt32',use_last_error=True);kernel=ctypes.WinDLL('kernel32',use_last_error=True)
    kernel.LocalFree.argtypes=[ctypes.c_void_p];kernel.LocalFree.restype=ctypes.c_void_p
    if decrypt:
        function=crypt.CryptUnprotectData
        function.argtypes=[ctypes.POINTER(Blob),ctypes.c_void_p,ctypes.c_void_p,ctypes.c_void_p,ctypes.c_void_p,wintypes.DWORD,ctypes.POINTER(Blob)]
        ok=function(ctypes.byref(source),None,None,None,None,1,ctypes.byref(target))
    else:
        function=crypt.CryptProtectData
        function.argtypes=[ctypes.POINTER(Blob),wintypes.LPCWSTR,ctypes.c_void_p,ctypes.c_void_p,ctypes.c_void_p,wintypes.DWORD,ctypes.POINTER(Blob)]
        ok=function(ctypes.byref(source),'ShelterConnect backup configuration',None,None,None,5,ctypes.byref(target))
    function.restype=wintypes.BOOL
    if not ok:raise RuntimeError('Windows protected configuration operation failed.')
    try:return ctypes.string_at(target.data,target.size)
    finally:kernel.LocalFree(target.data)

def current_sid():
    result=subprocess.run(['whoami.exe','/user','/fo','csv','/nh'],capture_output=True,text=True,check=True)
    sid=result.stdout.strip().split(',')[-1].strip('"')
    if not sid.startswith('S-1-5-'):raise RuntimeError('Cannot determine current user SID.')
    return sid

def restrict(path,sid):
    Path(path).mkdir(parents=True,exist_ok=True)
    result=subprocess.run(['icacls.exe',str(path),'/inheritance:r','/grant:r','*'+sid+':(OI)(CI)F','*S-1-5-18:(OI)(CI)F','*S-1-5-32-544:(OI)(CI)F'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    if result.returncode:raise RuntimeError('Backup directory protection failed.')

def protect_json(path,value):Path(path).write_bytes(transform(json.dumps(value).encode('utf-8')))
def read_json(path):return json.loads(transform(Path(path).read_bytes(),True))
