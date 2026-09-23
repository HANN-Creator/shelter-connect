"""Private development photo -> Luna appearance -> PixelLab BASE check. Explicit paid opt-in."""
from pathlib import Path
import argparse, hashlib, io, json, math, sys, uuid
from PIL import Image
from check_asset_pipeline_live import LiveCheck, CheckFailed, ConfigurationError, require, sql_literal, API, check_sheet

NOTE = '사용자가 제공하고 OpenAI 외형 분석 및 PixelLab 변환을 요청한 사진의 비공개 검증. 공개 게시·크롤링 허가가 아님.'

class PhotoCheck(LiveCheck):
    def prepare(self):
        self.setup()
        self.query(f'UPDATE shelter.asset_source_permissions SET permission_note={sql_literal(NOTE)} WHERE id={sql_literal(self.permission)} AND source_key={sql_literal(self.run)};')
        self.report.update(purpose='Private real-photo appearance and BASE verification',
                           paidSubmissionLimitForRun=1, expectedServerCommit=self.args.server_commit,
                           behaviorNote='Fixture observations are fictional, not claims about the photo')
        self.save()

    def upload_photo(self):
        picture=self.args.photo.read_bytes()
        with Image.open(io.BytesIO(picture)) as photo:
            require(photo.format=='PNG' and max(photo.size)<=1024, 'Use a rights-cleared PNG up to 1024px for this bounded check')
        (self.output/'input.png').write_bytes(picture)
        self.report['inputSha256']=hashlib.sha256(picture).hexdigest()
        boundary='shelter-'+uuid.uuid4().hex
        metadata={'clientUploadId':str(uuid.uuid4()),'permissionId':self.permission,'rightsConfirmed':True,'rightsNote':NOTE}
        payload=(f'--{boundary}\r\nContent-Disposition: form-data; name="metadata"\r\nContent-Type: application/json\r\n\r\n'.encode()
                 +json.dumps(metadata,ensure_ascii=False).encode()
                 +f'\r\n--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="private-photo-check.png"\r\nContent-Type: image/png\r\n\r\n'.encode()
                 +picture+f'\r\n--{boundary}--\r\n'.encode())
        headers={'Authorization':'Bearer '+self.token,'Content-Type':'multipart/form-data; boundary='+boundary}
        result=self.request('POST',API+self.dog_path+'/photos',payload,headers)['data']
        self.job=result['job']['id'];self.report.update(assetJobId=self.job,photoId=result['photoId']);self.save()
        repeated=self.request('POST',API+self.dog_path+'/photos',payload,headers)['data']
        require(repeated['job']['id']==self.job and repeated['photoId']==result['photoId'],'Photo replay must reuse the same job')
        self.passed('Actual photo upload API and idempotent replay')

    def download_base(self):
        job=self.wait_for('RIG_REVIEW')
        require(self.submissions()==1,'Expected exactly one PixelLab BASE submission')
        require(all(s['status']=='PENDING' for s in job['steps'] if s['action']!='BASE'),'Animations must wait for review')
        preparation=next(s['result']['preparation'] for s in job['steps'] if s['action']=='BASE')
        require(preparation['status']=='READY' and preparation['attempts']==1,'Appearance must complete exactly once')
        analysis=preparation['analysis']
        require(analysis['dogCount']==1 and analysis['headVisible'] is True,'Expected one dog with a visible head')
        require(set(analysis['features'])=={'coat','markings','ears','eyes','muzzle','nose','tail','build'},'Appearance fields missing')
        (self.output/'appearance.json').write_text(json.dumps(preparation,ensure_ascii=False,indent=2))
        # Reconstruct the crop from the server's persisted coordinates only for inspection.
        photo=Image.open(self.output/'input.png').convert('RGB');box=analysis['headBox']
        x,y,w,h=[box[k] for k in ('x','y','width','height')]
        crop=(max(0,math.floor((x-w*.12)*photo.width)),max(0,math.floor((y-h*.12)*photo.height)),
              min(photo.width,math.ceil((x+w*1.12)*photo.width)),min(photo.height,math.ceil((y+h*1.12)*photo.height)))
        photo.crop(crop).save(self.output/'head-reference-preview.png')
        rig=self.api('GET',self.job_path+'/rig')['data'];base=self.asset_bytes(rig['baseUrl'])
        check_sheet(base,1);(self.output/'base.png').write_bytes(base)
        self.api('GET','/v1/dogs/'+self.dog+'/assets',expected=(404,),anonymous=True)
        self.report.update(baseSha256=hashlib.sha256(base).hexdigest(),providerSubmissions=self.submissions(),
                           appearanceModel=preparation['model'],appearanceAttempts=preparation['attempts'],
                           publicationPerformed=False,headPreviewSource='Reconstructed from server analysis coordinates; no manual crop',
                           source='Render photo API -> Luna -> PixelLab -> Storage -> authenticated rig API')
        self.passed('Luna analysis saved once, actual server BASE downloaded, no public exposure, review gate preserved')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('project-ref','server-commit'):parser.add_argument('--'+name,required=True)
    for name in ('photo','env-file','storage-env-file','output-dir'):parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--psql');parser.add_argument('--ca-file',default='/etc/ssl/cert.pem')
    parser.add_argument('--timeout-seconds',type=int,default=1200);parser.add_argument('--allow-paid-calls',action='store_true')
    args=parser.parse_args()
    if not args.allow_paid_calls:parser.error('--allow-paid-calls is required; nothing was sent')
    if not 60<=args.timeout_seconds<=1800:parser.error('timeout-seconds must be 60..1800')
    check=None;failed=False
    try:
        check=PhotoCheck(args);check.prepare();check.upload_photo();check.download_base()
    except (Exception,KeyboardInterrupt) as e:
        failed=True;message=str(e) if isinstance(e,(CheckFailed,ConfigurationError)) else type(e).__name__
        print('FAIL '+message,flush=True)
        if check:check.report['failure']=message
    finally:
        if check:
            try:check.cleanup()
            except Exception:failed=True;check.report['cleanupNeedsAttention']=True;print('FAIL cleanup needs attention',flush=True)
            check.report['status']='FAILED' if failed else 'PASSED';check.save()
    return 1 if failed else 0

if __name__=='__main__':sys.exit(main())
