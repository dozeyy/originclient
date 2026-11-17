import sys,glob,os
from PIL import Image, ImageStat
for p in sorted(glob.glob(sys.argv[1])):
    im=Image.open(p).convert("L"); w,h=im.size
    c=im.crop((0,int(h*0.15),w,int(h*0.75)))  # skip HUD bar / compass
    print(os.path.relpath(p,sys.argv[2]), round(ImageStat.Stat(c).mean[0],1))
