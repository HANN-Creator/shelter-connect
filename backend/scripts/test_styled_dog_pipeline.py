"""Offline checks for identity/style separation, paid-call safety and pixel fidelity."""
import base64
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from PIL import Image
from styled_dog.client import Client, digest, native_image, read, write
from styled_dog.pipeline import (STYLE, load_rules, prepare, verify_inputs, character_request,
    record_review, require_review, motion_request, save_clip, animate)
from styled_dog.source import secure_photo_url, prepare_concept
from styled_dog.package import package, sheet_for, reviewed_frames


class PipelineTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        Image.new('RGB',(80,60),'tan').save(self.root/'photo-1.png')
        write(self.root/'source.json',{'desertionNo':'123','noticeNo':'sample','kindNm':'mix','careNm':'sample','weight':'6kg','colorCd':'tan'})
        self.traits = {'animalId':'123','sourcePhoto':'photo-1.png','sourcePhotoSha256':digest(self.root/'photo-1.png'),
            'photoReviewed':True,'reviewNote':'Floppy ears and sable back seen in actual photo.',
            'faceBox':[0.1,0.1,0.5,0.6],'identityDescription':'Tan puppy with floppy dark ears, dark muzzle and sable back. No white blaze.',
            'motionDescription':'Tan sable puppy, floppy ears and black muzzle.','rearDescription':'Rear view: sable back and tan hind legs; face hidden.',
            'seed':11,'unknownFeatures':['Unseen rear marking']}
        write(self.root/'traits.json',self.traits)
        prepare(self.root,self.root/'traits.json')
        (self.root/'directions').mkdir()
        for d in load_rules()['directions']:
            (self.root/'directions'/(d+'.png')).write_bytes((STYLE/'style.png').read_bytes())
        (self.root/'base.png').write_bytes((STYLE/'style.png').read_bytes())

    def review(self):
        record_review(self.root,'Reviewed photo likeness, ears, muzzle, rounded style and all four directions. Rear details inferred.', 'LOW')

    def test_seed_lessons_are_scoped_additions_for_initial_and_corrective_requests(self):
        from copy import deepcopy
        from styled_dog.quality import POLICY
        rule = {'id':'12345678-1234-1234-1234-123456789abc','sha256':'a'*64,
                'rulesSha256':digest(POLICY),'action':'BASE','direction':'all','tail':'UNKNOWN',
                'issue':'EYE_READABILITY','prevention':'Keep filled pupils distinct from adjacent fur.',
                'criterion':'Pupils must be readable as compact filled shapes in front and side views.'}
        for attempt in range(3):
            q={'rulesSha256':digest(POLICY),'attempt':attempt,'lessons':[deepcopy(rule) for _ in range(5)]}
            p=character_request(self.root,self.traits,load_rules(),q)
            self.assertIn(rule['prevention'],p['description'])
            self.assertIn('No hollow eye rings',p['description'])
            self.assertLessEqual(len(p['description']),2000)
        for change in [{'action':'WALK'},{'direction':'west'},{'tail':'HIGH'},{'rulesSha256':'old'},
                       {'issue':'TAIL_CARRIAGE'},{'prevention':'Run $(anything) now'}]:
            bad=deepcopy(rule);bad.update(change)
            with self.subTest(change=change),self.assertRaises(ValueError):
                character_request(self.root,self.traits,load_rules(),{'rulesSha256':digest(POLICY),'lessons':[bad]})

    def test_both_images_are_transmitted_with_distinct_roles(self):
        body = character_request(self.root,self.traits,load_rules())
        self.assertEqual(body['method'],'create_from_concept')
        self.assertEqual(base64.b64decode(body['concept_image']['base64']),(self.root/'photo-concept.png').read_bytes())
        self.assertEqual(base64.b64decode(body['reference_image']['base64']),(STYLE/'style.png').read_bytes())
        self.assertNotEqual(body['reference_image'],body['concept_image'])
        self.assertIn(self.traits['identityDescription'],body['description'])
        self.assertLessEqual(len(body['description']),2000)
        self.assertEqual(body['image_size'],{'width':32,'height':32})

    def test_photo_crop_includes_body_and_face_without_overwriting_photo(self):
        with Image.open(self.root/'photo-concept.png') as concept, Image.open(self.root/'face.png') as face:
            self.assertEqual(concept.size,(1024,512))
            self.assertEqual(face.size,(32,30))
        self.assertEqual(digest(self.root/'photo-1.png'),self.traits['sourcePhotoSha256'])

    def test_wrong_animal_and_invalid_or_unreviewed_face_crop_are_rejected(self):
        for change in ({'animalId':'other'},{'photoReviewed':False},{'sourcePhotoSha256':'other'},
                       {'faceBox':[0.5,0.1,0.1,0.5]},{'faceBox':[0,0,2,1]},{'sourcePhoto':'../private.png'}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                prepare_concept(self.root,dict(self.traits,**change))

    def test_run_and_seed_review_are_bound_to_immutable_inputs(self):
        self.review()
        require_review(self.root)
        sprite = Image.open(self.root/'directions/south.png').convert('RGBA')
        sprite.putpixel((0,0),(0,0,0,255))
        sprite.save(self.root/'directions/south.png')
        with self.assertRaises(ValueError):
            require_review(self.root)

    def test_changed_style_or_photo_cannot_silently_resume(self):
        for file in ('style-reference.png','photo-1.png','rules.json','traits.json','quality-rules.json'):
            with self.subTest(file=file):
                path = self.root/file
                previous = path.read_bytes()
                path.write_bytes(previous+b'changed')
                with self.assertRaises((ValueError,KeyError)):
                    verify_inputs(self.root)
                path.write_bytes(previous)

    def test_all_32_requests_have_correct_view_and_loop_semantics(self):
        self.review()
        rules = load_rules()
        for a in rules['actions']:
            for d in rules['directions']:
                b = motion_request(self.root,a,d)
                self.assertLessEqual(len(b['description']),1000)
                self.assertLessEqual(len(b['subject_description']),300)
                self.assertEqual(b['direction'],d)
                self.assertEqual(b['frame_count'],8)
                self.assertFalse(b['enhance_prompt'])
                self.assertEqual('last_frame' in b,rules['actions'][a]['loop'])
                if 'last_frame' in b:
                    self.assertEqual(b['last_frame'],b['first_frame'])
                if d == 'north':
                    self.assertIn('No visible eyes',b['description'])
                    self.assertEqual(b['subject_description'],self.traits['rearDescription'])

    def test_native_frame_validation_never_downscales(self):
        for size,color in (((64,64),(1,1,1,255)),((32,32),(1,1,1,255)),((32,32),(0,0,0,0))):
            b = io.BytesIO()
            Image.new('RGBA',size,color).save(b,format='PNG')
            with self.assertRaises(ValueError):
                native_image(b.getvalue())

    def test_transparency_and_pixel_coordinates_survive_sheet_packaging(self):
        f = Image.new('RGBA',(32,32))
        f.putpixel((11,12),(12,34,56,78))
        sheet = sheet_for([f,f])
        self.assertEqual(sheet.crop((32,0,64,32)).tobytes(),f.tobytes())
        self.assertEqual(sheet.getpixel((43,12)),(12,34,56,78))

    def test_allowlist_bundle_and_partial_coverage_are_honest(self):
        self.review()
        frames = [{'base64':base64.b64encode((STYLE/'style.png').read_bytes()).decode()}]*9
        save_clip(self.root,'BACK_OFF','west',{'last_response':{'images':frames}})
        (self.root/'raw').mkdir(exist_ok=True)
        (self.root/'raw'/'secrets.txt').write_text('never published')
        (self.root/'.env').write_text('NEVER=publish')
        package(self.root)
        m = read(self.root/'manifest.json')
        self.assertEqual(m['status'],'PARTIAL')
        self.assertEqual(len(m['missingClips']),31)
        self.assertEqual(m['animations']['back_off-west']['worldMotion']['unitVector'],{'x':1,'y':0})
        self.assertFalse(m['published'])
        import zipfile
        with zipfile.ZipFile(self.root/'styled-dog-assets.zip') as z:
            self.assertFalse(any(n.startswith(('raw/','plan/')) or n.endswith('.env') for n in z.namelist()))

    def test_source_url_allows_only_government_photo_host_without_redirect(self):
        self.assertEqual(secure_photo_url('http://openapi.animal.go.kr/image.jpg'),'https://openapi.animal.go.kr/image.jpg')
        for url in ('file:///private/key','http://localhost/photo','https://openapi.animal.go.kr.evil/a',
                    'https://user@openapi.animal.go.kr/a','https://openapi.animal.go.kr:443/a'):
            with self.subTest(url=url), self.assertRaises(ValueError):
                secure_photo_url(url)

    def test_failed_raw_clip_cannot_be_published_by_holding_an_earlier_frame(self):
        self.review()
        seed=(STYLE/'style.png').read_bytes()
        bad=Image.open(io.BytesIO(seed)).convert('RGBA');bad.putpixel((31,16),(1,2,3,128))
        encoded=io.BytesIO();bad.save(encoded,format='PNG')
        frames=[{'base64':base64.b64encode(seed).decode()} for _ in range(9)]
        frames[-1]={'base64':base64.b64encode(encoded.getvalue()).decode()}
        with self.assertRaisesRegex(ValueError,'CANVAS_CLIPPING'):
            save_clip(self.root,'SIT','north',{'last_response':{'images':frames}})
        clip=read(self.root/'clips/sit-north.json')
        write(self.root/'frame-reviews.json',{'clips':{'sit-north':{'holdFromFrame':7,
            'rawFrameSha256':clip['frameSha256'],'reason':'Hide a clipped final frame'}}})
        with self.assertRaisesRegex(ValueError,'CANVAS_CLIPPING'):
            package(self.root)
        self.assertEqual(read(self.root/'quality-audit.json')['clips']['sit-north']['edgeFrames'],[8])
        self.assertTrue((self.root/'frames/sit-north/08.png').exists())
        self.assertFalse((self.root/'styled-dog-assets.zip').exists())

    def test_all_32_jobs_pass_the_real_journal_and_reuse_on_second_run(self):
        import hashlib
        import json
        self.review()
        client = Client(self.root,'test-only',True)
        jobs = {}
        def request(method, endpoint, body=None):
            if method == 'POST':
                job = hashlib.sha256(json.dumps(body,sort_keys=True).encode()).hexdigest()
                jobs[job] = body['first_frame']
                return {'background_job_id':job}
            if endpoint == 'balance':
                return {}
            job = endpoint.split('/')[-1]
            return {'status':'completed','last_response':{'images':[jobs[job]]*9},'usage':{'generations':1}}
        with patch.object(client,'request',side_effect=request) as remote:
            write(self.root/'failures.json',[{'error':'previous recovered failure'}])
            animate(self.root,client,None)
            self.assertEqual(len(jobs),32)
            self.assertEqual(read(self.root/'failures.json'),[])
            self.assertEqual(len(list((self.root/'clips').glob('*.json'))),32)
            first_count = sum(c.args[0] == 'POST' for c in remote.call_args_list)
            animate(self.root,client,None)
            self.assertEqual(sum(c.args[0] == 'POST' for c in remote.call_args_list),first_count)

    def test_job_labels_cannot_escape_the_run_directory(self):
        client = Client(self.root,'test-only',True)
        with patch.object(client,'request') as remote:
            for label in ('../other','/tmp/other','tail_wag/../../other',''):
                with self.subTest(label=label), self.assertRaises(ValueError):
                    client.generate(label,'animate-pixminimax',{})
            remote.assert_not_called()

    def test_frame_hold_cannot_hide_a_known_bad_trailing_pose(self):
        frames = [Image.new('RGBA',(32,32),(i,0,0,255)) for i in range(9)]
        clip = {'label':'sit-north','loop':False,'frameSha256':list(range(9))}
        write(self.root/'frame-reviews.json',{'clips':{'sit-north':{'holdFromFrame':7,
            'rawFrameSha256':list(range(9)), 'reason':'Frame 8 incorrectly shows the face'}}})
        before=[f.tobytes() for f in frames]
        with self.assertRaisesRegex(ValueError,'Frame substitution cannot hide'):
            reviewed_frames(self.root,clip,frames)
        self.assertEqual([f.tobytes() for f in frames],before)



class PaidJournalTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.client = Client(self.root,'test-only',True)

    def test_ambiguous_post_is_never_reissued(self):
        with patch.object(self.client,'request',side_effect=TimeoutError) as request:
            with self.assertRaises(TimeoutError):
                self.client.generate('sample','create-character-pro',{'x':1})
            with self.assertRaisesRegex(ValueError,'outcome unknown'):
                self.client.generate('sample','create-character-pro',{'x':1})
            self.assertEqual(request.call_count,1)

    def test_completed_run_reuses_result_and_refuses_endpoint_or_payload_drift(self):
        result = {'status':'completed','last_response':{'images':[]},'usage':{'generations':1}}
        with patch.object(self.client,'request',side_effect=[{'background_job_id':'job'},result]) as request:
            self.assertEqual(self.client.generate('sample','animate-pixminimax',{'x':1}),result)
            self.assertEqual(self.client.generate('sample','animate-pixminimax',{'x':1}),result)
            self.assertEqual(request.call_count,2)
            for endpoint,body in [('different',{'x':1}),('animate-pixminimax',{'x':2})]:
                with self.assertRaises(ValueError):
                    self.client.generate('sample',endpoint,body)

    def test_accepted_job_resumes_with_get_only(self):
        with patch.object(self.client,'request',side_effect=[{'background_job_id':'job'},TimeoutError]):
            with self.assertRaises(TimeoutError):
                self.client.generate('sample','animate-pixminimax',{})
        with patch.object(self.client,'request',return_value={'status':'completed','last_response':{}}) as request:
            self.client.generate('sample','animate-pixminimax',{})
            request.assert_called_once_with('GET','background-jobs/job')

    def test_ack_is_recovered_after_state_write_crash(self):
        with patch.object(self.client,'request',side_effect=TimeoutError):
            with self.assertRaises(TimeoutError):
                self.client.generate('sample','animate-pixminimax',{})
        write(self.root/'raw/sample-ack.json',{'background_job_id':'ack-job'})
        with patch.object(self.client,'request',return_value={'status':'completed','last_response':{}}) as request:
            self.client.generate('sample','animate-pixminimax',{})
            request.assert_called_once_with('GET','background-jobs/ack-job')

    def test_failed_jobs_and_non_opted_in_calls_do_not_retry(self):
        with patch.object(self.client,'request',side_effect=[{'background_job_id':'job'},{'status':'failed'}]) as request:
            for _ in range(2):
                with self.assertRaises(ValueError):
                    self.client.generate('sample','animate-pixminimax',{})
            self.assertEqual(request.call_count,2)
        self.client.allow_paid = False
        with patch.object(self.client,'request') as request, self.assertRaises(ValueError):
            self.client.generate('new','animate-pixminimax',{})
        request.assert_not_called()


if __name__ == '__main__':
    unittest.main()
