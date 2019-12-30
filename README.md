### Real-Time Image Detection for Live Video Using AWS Rekognition Through Android App

<br> 

Nowadays there is a huge video data collection present on the internet. To make
videos search efficient, users are prompted to enter specific tags related to
the video after they have uploaded it. Tags are used as metadata for your
video’s discovery. There is no guarantee that users have entered valid tags
related to the video, users can enter the trending tags just to get more
impressions.

**How can we solve this problem?**

We can automate video tagging and make application smart enough to process the
user’s video and return the most related tags accordingly.

I did a POC around this at Nirman, by making an android application to do
real-time tag detection. I am sharing my learnings below.

### Usecases

* It can help media companies to quickly summarize and organize large video
catalogs.
* It can also help to improve video recommendations, as it enables the search
engines to consider video content, beyond the video metadata.
* It can help in video surveillance, where many hours of videos must be searched
for a specific event.
* Moreover, Internet platforms, such as YouTube and Facebook, would be able to
automatically identify and remove videos with illegal content.

### Architecture

![](https://cdn-images-1.medium.com/max/800/1*zBLeSlfewAMNV9zUAPK6Vg.jpeg)

### **How does it work?**

A simple approach is to treat video frames as still images and apply ML
algorithms to recognize each frame and then average the predictions at the video
level. 

Here we are going to see an application of label detection from a live video
that is captured through an android app. It can be achieved through the AWS
Rekognition service. Frames will be taken at the frequent intervals (Say for
each sec) from a live video and frame will be analyzed by AWS Rekognition
Service asynchronously and response tags will be displayed immediately in
android.

> **Note** : Processing all video frames is computationally inefficient even for
> short video clips, since each video might contain thousands of frames(consider
24 frames per second for videos). Moreover, consecutive video frames
significantly overlap with each other in content and not all frames are
consistent with the overall story of the video.

### Prerequisites

Below are the pre-requisites in order to set up for Label detection from a live
video.

1. Need to install [Android Studio](https://developer.android.com/studio)
according to Operation System.<br>  2. Signup for [Amazon AWS
Account](https://portal.aws.amazon.com/billing/signup) and should have full
access to [Rekognition service](https://aws.amazon.com/rekognition/).

### Setting Up the Application

* Clone the git repository to the local folder

    $ git clone git@github.com:Karthi96/Video-Recorder-with-Frames-Analysis.git

* Open the project in android studio and you can able to see the screen as below.

![](https://cdn-images-1.medium.com/max/800/1*29108ShDzpnk7yyqpQE2dg.png)
<span class="figcaption_hack">Project Structure</span>

* Make sure the dependencies of `aws-android-sdk-rekognition, opencv and ffmpeg`
has been added to Gradle file path `/app/build.gradle`.
```
implementation 'com.amazonaws:aws-android-sdk-rekognition:2.10.0'
implementation group: 'org.bytedeco', name: 'javacv', version: '1.3.2'
implementation group: 'org.bytedeco.javacpp-presets', name: 'opencv', version: '3.2.0-1.3', classifier: 'android-arm'
implementation group: 'org.bytedeco.javacpp-presets', name: 'opencv', version: '3.2.0-1.3', classifier: 'android-x86'
 implementation group: 'org.bytedeco.javacpp-presets', name: 'ffmpeg', version: '3.2.1-1.3', classifier: 'android-arm'
 implementation group: 'org.bytedeco.javacpp-presets', name: 'ffmpeg', version: '3.2.1-1.3', classifier: 'android-x86'

* Configure the AWS account into the android app by providing `accessKey and
secretKey` to the file path
`/app/src/main/java/nirman/io/detector/AwsConfig.java`. If you don't know how to
generate the accessKey and secretKey means refer to this
[link](https://docs.aws.amazon.com/general/latest/gr/managing-aws-access-keys.html).

![](https://cdn-images-1.medium.com/max/800/1*gJOkWkzg-Qs9ftvwolW52A.png)
<span class="figcaption_hack">AwsConfig file</span>

* Then add the `framesAnalysis` value for analyzing the video frames at a certain
time interval of frames in the file path
`/app/src/main/java/nirman/io/detector/AwsConfig.java`. **The universally
accepted film frame rate is 24 fps(Frame Per Second). **Some standards support
25 fps and some high definition cameras can record at 30, 50 or 60 fps. i.e If
framesAnalysis=24 means for each 24th frame will be captured and send to AWS for
analysis.
* Here is the code to get frames through onPreviewFrame() function and it will be
called if the camera started capturing. We will be getting the frames
continuously and need to get a particular frame at a certain interval of time
according to your business requirement accuracy.

```
@Override
public void onPreviewFrame(byte[] raw, Camera cam) {
Camera.Parameters parameters = cam.getParameters();
int width = parameters.getPreviewSize().width;
int height = parameters.getPreviewSize().height;
YuvImage yuv = new YuvImage(raw, parameters.getPreviewFormat(), width, height, null);
ByteArrayOutputStream out = new ByteArrayOutputStream();
yuv.compressToJpeg(new Rect(0, 0, width, height), 50, out);
byte[] bytes = out.toByteArray();
AsyncAWSImageDetection runner = new AsyncAWSImageDetection();

runner.execute(bytes);
}
```

* Here is the code for the AWS Rekognition API call to process each frame and it
was in async class so that it will not wait and a continues process will happen.
```
@Override
protected String doInBackground(byte[]... params) {
try{
public AmazonRekognition rekognitionClient = new AmazonRekognitionClient(new BasicAWSCredentials(AwsConfig.accessKey, AwsConfig.secretKey));
ByteBuffer sourceImageBytes = ByteBuffer.wrap(params[0]);
Image source = new Image().withBytes(sourceImageBytes);
DetectLabelsRequest request = new DetectLabelsRequest()
               .withImage(source)
               .withMaxLabels(10).withMinConfidence(75F);
DetectLabelsResult detectLabelsResult = rekognitionClient.detectLabels(request);
List<Label> detected=detectLabelsResult.getLabels();
}catch(Exception e) {
  e.printStackTrace();    
}
}
```

### Run the Application

Now everything is set it up, you can run the application either on a real device
or through an emulator. Refer to this
[link](https://developer.android.com/training/basics/firstapp/running-app), if
you don’t how to run the application.

### **Application Output**

https://www.youtube.com/watch?v=gPkD8HfNqUI

### Conclusion

From the above exercise, you have learned how to detect the labels from live
video with AWS recognition service through Mobile application.

Thank you **Sridhar Babu Kolapalli Sir** for helping me to do this PoC.

Thank you for reading and keep following
[Nirman-Tech](https://medium.com/nirman-tech-blog) to see more such posts.

![](https://cdn-images-1.medium.com/max/800/1*nKFhsRpAlPL1UDFlzAcIng.gif)

<br> 
