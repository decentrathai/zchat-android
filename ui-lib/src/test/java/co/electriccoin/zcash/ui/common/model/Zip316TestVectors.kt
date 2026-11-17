package co.electriccoin.zcash.ui.common.model

/*
 * ZC-1082 test data, all public and made outside this code base:
 *  - [ZIP316_UNIFIED_ADDRESSES]: every Unified Address vector of zcash-test-vectors `unified_address.py`, as librustzcash
 *    ships it (`components/zcash_address/src/kind/unified/address/test_vectors.rs`, clone at 8dfc254da): the address and
 *    its receivers (ZIP 316 typecode, raw bytes in hex), unknown typecodes included.
 *  - [F4JUMBLE_VECTORS]: the first six F4Jumble vectors of `f4jumble.py` (`components/f4jumble/src/test_vectors.rs`), and
 *    [F4JUMBLE_LONG_VECTORS] of `f4jumble_long.py` (message byte i = i mod 256; BLAKE2b-512 of the jumbled message).
 *  - [BLAKE2B_PERSONAL_VECTORS]: BLAKE2b with a personalisation, computed with Python 3 `hashlib.blake2b` (an independent
 *    implementation): output length, personalisation, message and digest, in hex.
 */

internal class Zip316Vector(
    val address: String,
    /** typecode to raw receiver bytes (hex), in the address's order. */
    val receivers: List<Pair<Int, String>>,
)

internal val ZIP316_UNIFIED_ADDRESSES: List<Zip316Vector> =
    listOf(
        Zip316Vector(
            "u1l8xunezsvhq8fgzfl7404m450nwnd76zshscn6nfys7vyz2ywyh4cc5daaq0c7q2su5lqfh23sp7fk" +
                "f3kt27ve5948mzpfdvckzaect2jtte308mkwlycj2u0eac077wu70vqcetkxf",
            listOf(
                0 to
                    "7bb83570b8fae146e03c5331a020b1e0892f631d",
                2 to
                    "d8ef8293d26de832e7193f296ba1922d90f122c6135bc231eebd91efdb03b1a8606771cd4fd64805" +
                    "74d43e",
            ),
        ),
        Zip316Vector(
            "u1fl5mprj0t9p4jg92hjjy8q5myvwc60c9wv0xachauqpn3c3k4xwzlaueafq27dcg7tzzzaz5jl8tyj" +
                "93wgs983y0jq0qfhzu6n4r8rakpv5f4gg2lrw4z6pyqqcrcqx04d38yunc6je",
            listOf(
                0 to
                    "a7244a362f49f29644a955cf0039b88a61657861",
                2 to
                    "435b0bbc95b5b7d52531a3944f2b85603ee22aaf850963bc156eb561edf2cbe7cf0e770e393ae5d7" +
                    "049026",
            ),
        ),
        Zip316Vector(
            "u1qxqf8ctkxlsdh7xdcgkdtyw4mku7dxma8tsz45xd6ttgs322gdk7kazg3sdn52z7na3tzcrzf7lt3x" +
                "rdtfp9d4pccderalchvvxk8hghduxrky5guzqlw65fmgp6x7aj4k8v5jkgwuw",
            listOf(
                0 to
                    "e256dcb03e05dde7c91212b47a7461311c415059",
                2 to
                    "69a25a38699708e5f6e76e54e6a7a2ab84dcf288df0d1f2563670168d6c44ace0ef11155c60d5c22" +
                    "5e9dec",
            ),
        ),
        Zip316Vector(
            "u1pg2aaph7jp8rpf6yhsza25722sg5fcn3vaca6ze27hqjw7jvvhhuxkpcg0ge9xh6drsgdkda8qjq5c" +
                "hpehkcpxf87rnjryjqwymdheptpvnljqqrjqzjwkc2ma6hcq666kgwfytxwac8eyex6ndgr6ezte6670" +
                "6e3vaqrd25dzvzkc69kw0jgywtd0cmq52q5lkw6uh7hyvzjse8ksx",
            listOf(
                0 to
                    "cad268758c5e71493066446b98e71df9d1d6a5ca",
                2 to
                    "9f6e0bf90a18fc0b9b83ae9f23ad4358648638482b5def8975635b66fd8a708335f9235a3186ec0f" +
                    "033f84",
                3 to
                    "cecbe5e689a453a3fe10ccf7617e6c1fb382819d7fc9200a1f42092ac84a30378f8c1fb90dff71a6" +
                    "d5042d",
            ),
        ),
        Zip316Vector(
            "u19mzuf4l37ny393m59v4mxx4t3uyxkh7qpqjdfvlfk9f504cv9w4fpl7cql0kqvssz8jay8mgl8lnrt" +
                "vg6yzh9pranjj963acc3h2z2qt7007du0lsmdf862dyy40c3wmt0kq35k5z836tfljgzsqtdsccchayf" +
                "jpygqzkx24l77ga3ngfgskqddyepz8we7ny4ggmt7q48cgvgu57mz",
            listOf(
                0 to
                    "8d653347a0fd3cd0842a790a5eaf89d8e3854659",
                2 to
                    "e1adf156a07d56bcac91bdb2f7bb3ea7c44569dcfee54273c09e8065807b6823faa94a77219554d0" +
                    "f6e017",
                3 to
                    "24f8a60cbd97e012618d56054ad39241411a28fdd50ee35efa91152f60d5fa21172e5d458ddbcb6b" +
                    "709896",
            ),
        ),
        Zip316Vector(
            "u1mtxw5nras5glkxz093282sv3n2h8qs7cpxcmmaxj96vtzjzl6rmdaxs4e9es7mxwmd0h3k5wz3ce4l" +
                "l5g4jz2pn9su4pufq74pxhp4t235n6j7aed3hh8ss7pf3sekf7apsf6vtg84ue5zcq2k9q3xv5yth3q5" +
                "0fu4czdm8sn8q4de3m5k76g2vwwyjsf50hqfxgmwxqxu0rsy22ktw",
            listOf(
                0 to
                    "e511f439b5f96cf824cd5e0e6b2eb8ee1bc83cb7",
                2 to
                    "60ba572f8e379312d86897025decdd64b4b95e2c4afa9d13726b8cc393edb4988c51b976028f890f" +
                    "108bd2",
                3 to
                    "1f24294ed1b405c7b3b1c3f13db5b9b27b5d0f2aca9d589a69e5be00eb978621e6776e87ea326d47" +
                    "a34c1a",
            ),
        ),
        Zip316Vector(
            "u1ay3aawlldjrmxqnjf5medr5ma6p3acnet464ht8lmwplq5cd3ugytcmlf96rrmtgwldc75x94qn4n8" +
                "pgen36y8tywlq6yjk7lkf3fa8wzjrav8z2xpxqnrnmjxh8tmz6jhfh425t7f3vy6p4pd3zmqayq49efl" +
                "2c4xydc0gszg660q9p",
            listOf(
                2 to
                    "88533c398a49c2513dc85162bf220abaf47dc983f14e908ddaaa7322dba16531bc62efe750fe575c" +
                    "8d149b",
                3 to
                    "953f3c78d103c32b60559299462ebb27348964b892acad10482fe502c99f0d524959ba7be4f188e3" +
                    "a27138",
            ),
        ),
        Zip316Vector(
            "u19a4vmx7ysmtavmnaz4d2dgl9pyshexw35rl5ezg5dkkxktg08p42lng7kf9hqtn2fhr63qzyhe8gtn" +
                "vgtfl9yvne46x6zfzwgedx7c0chnrxty0k5r5qqph8k02zs8e3keul9vj8myju7rvqgjaysa9kt0fucx" +
                "pzuky6kf0pjgy0a6hx",
            listOf(
                2 to
                    "616fe1a9d887148d6ca10f48ccd92d0dcad24f7c4c9d73ee8122b1766459b04dac4dc07e80edb9d2" +
                    "29bbbc",
                3 to
                    "cc802699330bc4748e34dd598c7124e72299e6a6d5bcc32e90409c8024868b2705aadfab6068d458" +
                    "f69b0c",
            ),
        ),
        Zip316Vector(
            "u13p2teem3xlvy4kwlke24hng5el2z6mn4ftj8xarwn8fy7dqt0flgcfpaxe6sk5cwawwh4tynzu7z2u" +
                "schaf8tfa3tp2xgt8g4kx5lahhglcjm26jnvw7am6ld33708g0kv35pq83eg6gj82a0aau80enrhywpg" +
                "r4v4m4vve7tg8vd4hz",
            listOf(
                2 to
                    "9304f6e3c889829a0a48f2ebdc0803bbbd393ebf4264e45cb7db793e9376fa85ddf31f5024e0bf79" +
                    "6672be",
                3 to
                    "3ed501c9c63abaf4d0136821f9647e764555a47033ad91d734df12d046c969751330bbf493a241ec" +
                    "4b88bc",
            ),
        ),
        Zip316Vector(
            "u1snf9yr883aj2hm8pksp9aymnqdwzy42rpzuffevj35hhxeckays5pcpeq7vy2mtgzlcuc4mnh9443q" +
                "nuyje0yx6h59angywka4v2ap6kchh2j96ezf9w0c0auyz3wwts2lx5gmk2sk9",
            listOf(
                0 to
                    "871a089d446268aa7ac03d2a6f60ae70808f3974",
                3 to
                    "31844683a07bf8e30057902b0d23e2b2ce9cad0b22190238ca4f329da92c7979052b00f735cb2106" +
                    "71bdb0",
            ),
        ),
        Zip316Vector(
            "u1szwcx2zdxalyp7cfqwrptv95rnpyajejs6jmwacz4cgm2g3vzdxl5perhpg3nyhnuplvptdr4g63gu" +
                "pdfj5zal9v35s3e6adqsckv68hyrclan3gxaj6mz8aejzsnhqjyn32jcpnpra",
            listOf(
                0 to
                    "7cb07c31b58040ac7cc12bfaaa138cfbefb38457",
                3 to
                    "05683c0303858388a785b4cf15d41ac69e1d435b0ad23838e18d62f7ec41c37fc86af71dffd94dff" +
                    "f6b207",
            ),
        ),
        Zip316Vector(
            "u1glq6lzrxc7n7r4c922qht20zmpxyl0asfuldrjcaddagfspxpc3040fdfwdf5crw4j6j6wkx4r038s" +
                "0w24w7enpyfmmdfu9t9p2amxazgvasms8l03l3j5yhrrfqy6xzue5uggef4p8",
            listOf(
                0 to
                    "3e02e08b5965fce9c20ce6de6f9407674d01ba02",
                3 to
                    "551a16fb00d5482a2ab25182560661cfd74a60fe77a0f1c9347f16ba5249889f3ae346ed6938c30a" +
                    "bfaf80",
            ),
        ),
        Zip316Vector(
            "u1tqhg04ppjt6vlf2uvkygt07sqzgpclxdpn7j7ydkcr0e8ym68wn592z7uqudktrwn4u3q57flp8hw3" +
                "d0wd9t0rm0e6m8eys27evfawh6zhha6eulzj86uz89swu7gtk0vcknd3dauhc96twhx20xxsp93dxahq" +
                "lt7z5p04ldgy2y2lp0",
            listOf(
                2 to
                    "6493348e8aee112a87f5fa65e1c57065aad369401e05d0daa96e0bcd89e67bf19beb3ac74d599d94" +
                    "585a68",
                3 to
                    "165082de84f2ad7204426ffafd6b6c7de9cab6d25c13846a1786715268c415948db788f4a5e0daa0" +
                    "3d699e",
            ),
        ),
        Zip316Vector(
            "u1zm98xj3ncc79sx8jxhcscptxav0p4wam8mlkf4lp69rhramz7v6fsndwxcd4qtmzkefwcwn5rgd8uz" +
                "tvdrvfqv32jk3xx6wlt7gae9fhs7xh48d3kn9fe92xtcff8hu0zgegmgr95qtxayjylfdct96eg2f2r0" +
                "6drf6sj800mcsns3n0",
            listOf(
                2 to
                    "65b6b03f7b27189cc0ed54bcf6bd938e39bfd1bf66b8a038c0a967fbc50e48c18da3de20d671858b" +
                    "8f7fbf",
                3 to
                    "c906109b51e2b37bf8b67761bfa917dc5059c357b7dc8107672b66189a0d15bc496d84ef9114c68c" +
                    "99c911",
            ),
        ),
        Zip316Vector(
            "u1hfgf2s4pghqteculnmq2rcnvyesml74zqfp5yfhxhwewx62q75qhgmwreg5qht7c5vu3fxefunjrar" +
                "rfhmcuw2z4ndx0qx7u74gkw2n7v0ypvd4mxgzlenvs7lkurdj09zuhz6pmtuzs4m42sx92axuuru4dmg" +
                "u46a920x5kuye6gxvs",
            listOf(
                2 to
                    "e987a4f50c94ba88e048638ecec706ef8a162674c9bef8caedfdf4b2131b451559090488ffe29ec0" +
                    "2abac1",
                3 to
                    "7cd065b0ab297fb7fd701291d03589031fe3aadf1177902e5bcb65b5ba0aa2a0b73f09734f0b867b" +
                    "29763d",
            ),
        ),
        Zip316Vector(
            "u17cfcut587e3kszg8vud0z5a8lj9gyypyvtt5xn4hfc4p3kv4e0jfr2pzzxhywlkhsjldtmkvupwr7m" +
                "kjvruz8gnxk7a64x777p4l3u7vpm6zsdsx88ef90x5q5sqx57fq8vtj5vk3hx",
            listOf(
                0 to
                    "40c44030e468b7091e9bb33ba0abdc63986f3c36",
                3 to
                    "ea9df83fbee07d6f7895ebb2ea41ec7c4ba682b863e069b4a438e31c9571c83126c305d75456412a" +
                    "eaef1b",
            ),
        ),
        Zip316Vector(
            "u1z6qgxh0wyw0ptgwwgsr5uv05n3xm3z8yrdr06k7q6fj9ypyjcj2hxwfmktv4a7ejaqphcgkddhsvrs" +
                "93skzl3frm8e48at6huayg7k67e3c50ykpdnhva2jfh5dfcvy6nvttqwgz5a7",
            listOf(
                0 to
                    "937e71f9b2b6440a05ee1475bcc487e08a4f5801",
                3 to
                    "fd3e7eccdb1a91f2c4498bb7eb61cba83eca499cfde9c5ce3e3241873bad2e423abe91dece0a6930" +
                    "e8901d",
            ),
        ),
        Zip316Vector(
            "u1g6jcyfwqd9yx8pdg4yvf0nsr5j7k5gmx83shh8v0v3w256umheen026x66f4608w2vydyasphgp80j" +
                "9avq9h56dx73gg2559l5lj707v4458a0ucyhfxcjcccfx9z9upmcf3c6hg9k8",
            listOf(
                0 to
                    "b34866819053983231c48fd8a2706cecff29ba99",
                3 to
                    "5ef3c8b2bf2a8b0e60a6254f312229b4124d4787e7dada5d81e16b51211707871bede32811a35f40" +
                    "94ae8b",
            ),
        ),
        Zip316Vector(
            "u1en8ysypun4gdkdnu8zqqg6k73ankr9ffwfzg08wtzg9z939w0wupewemfrc8a630e8gc4uqucym0l4" +
                "v44fszy3et4veyypt3jsyp0whfpfsn2lw30kj8nepe6wvvasf00wklh85u9v8glqndupmamk9z2ja9sa" +
                "nf70pp4yxvkt3dmyzxa0kkhv2c9pxmkghrxqk0590azvya3nzrtevj449nu3laskrhf7c7nj9cyw7ty3" +
                "8mccg4znrr876guu6pzndx7ngwzhmlsn8d89saf5araaacrhr9958xr6z23mj4qtzzn98whdpu8u7n8f" +
                "hf5d2vypljda62q73du44sf0e0kxmq3gvgkta0qqgq9w6r403gc5jz2any02etmwlttkv84hgh95czhd" +
                "f2jugk3u36ke0kchcthg240",
            listOf(
                0 to
                    "06974d8bcd8ba8ef89ce36a653d93868251c2e3d",
                3 to
                    "3c40246912b6efefab9a55244ac2c174e1a9f8c0bc0fd526933963c6ecb9b84ec8b0f6b40dc858fa" +
                    "23c72b",
                65532 to
                    "d56a1d62f5a8d7551db5fd9313e8c7203d996af7d477083756d59af80d06a745f44ab023752cb5b4" +
                    "06ed8985e18130ab33362697b0e4e4c763ccb8f676495c222f7fba1e31defa3d5a57efc2e1e9b01a" +
                    "035587d5fb1a38e01d94903d3c3e0ad3360c1d3710acd20b183e31d49f25c9a138f49b1a537edcf0" +
                    "4be34a9851a7af9db6990ed83dd64af3597c04323ea51b0052ad8084a8b9da948d320dadd64f5431" +
                    "e61ddf658d24ae67c22c8d13",
            ),
        ),
        Zip316Vector(
            "u1a7gz63aey4tnj4klwauth00vnkmltwafwzk9nld2ys7yz3yjzjcdp47crc37zc4g9aq4athg9zh8r7" +
                "92e44kd6g2f4drhsl5ph4ja8pe4gcc9yjyf3rn7pej808hcy6xh0x6y8khmzljehjlwqq4h2czp35vu3" +
                "l7aa7rpw5vcng9gswwlaqn5ptes592wejx7f49rxsvmzeqjekjtyfevehanvyksa8gtkpk75yrqnam26" +
                "hzuxrtm6agaluy4hv0ha4sg6h22394m0x5th6r8uj7svzlklaja852vv9ud5gznu2sqyrsqveqjmfk9r" +
                "cs59sprjj8nrt2nke862xlhvjq9y9zswen27eqj5slg52q2zch59uzwaeat8jw6z6092uu8yqqnnj7h0" +
                "yguhypgd8y2wu9ftgg38ym3",
            listOf(
                0 to
                    "cdd4b2be1b57f24c85fc1e43c77bb2da2d2646f1",
                3 to
                    "fc235122892d611e52ee5b447a77ec5a296213948fb56d721f66f264e32e7d0ce5473005fc4c0bcf" +
                    "421e8f",
                65532 to
                    "09131fc00fe7f235734276d38d47f1e191e00c7a1d48af046827591e9733a97fa6b679f3dc601d00" +
                    "8285edcbdae69ce8fc1be4aac00ff2711ebd931de518856878f73476f21a482ec9378365c8f7393c" +
                    "94e2885315eb4671098b79535e790fe53e29fef2b3766697ac32b4f473f468a008e72389fc03880d" +
                    "780cb07fcfaabe3f1a84b27db59a4a153d882d2b2103596555ed9494c6ac893c49723833ec8926c1" +
                    "039586a7afcf4a0d9c731e98",
            ),
        ),
        Zip316Vector(
            "u1ln90fvpdtyjapnsqpa2xjsarmhu3k2qvdr6uc6upurnuvzh382jzmfyw40yu8avd2lj7arvq57n0qm" +
                "ryy0flp7tm0fw05h366587mzzwwrls85da6l2sr7tuazmv5s02avxaxrl4j7pau0u9xyp470y9hkca5m" +
                "9g4735208w6957p82lxajzq4l2pqkam86y6jfx8cd8ecw2e05qnh0qq95dr09sgz9hqmflzac7hsxj47" +
                "yvjd69ej06ewdg97wsu2x9wg3ahfh6s4nvk65elwcu5wl092ta38028p4lc2d6l7ea63s6uh4ek0ry9l" +
                "g50acxuw2sdv02jh90tzh783d59gneu8ue3wqefjmtndyquwq9kkxaedhtqh2yyjew93ua38vp8uchug" +
                "0q7kg7qvp4l65t9yqaz2w2p",
            listOf(
                0 to
                    "9f98c3116cb2f4e6f4c814148c81e379a538ced3",
                3 to
                    "2526ec6552f3e0175c922f019077146b5193e880461c3e1daca4778cde010ed5875f16b743ef86ac" +
                    "648b3d",
                65532 to
                    "5d99589c8bb838e8aaf745533ed9e8ae3a1cd074a51a20da8aba18d1dbebbc862ded42435e924769" +
                    "30d069896cff30eb414f727b89e001afa2fb8dc3436d75a4a6f26572504b192232ecb9f0c02411e5" +
                    "2596bc5e90457e745939ffedbd12863ce71a02af117d417adb3d15cc54dcb1fce467500c6b8fb86b" +
                    "12b56da9c382857deecc40a98d5f2935395ee4762dd21afdbb5d47fa9a6dd984d567db2857b927b7" +
                    "fae2db587105415d4642789d",
            ),
        ),
        Zip316Vector(
            "u1sem2gcey0emntrvxyjv8hyhq0w5fr4sxaj3cppgrfqgg6laydh8m78gy2cw2p54zzak3alnnsx4xju" +
                "hazpkrfcd90wl0c7ldj6y095hh5j6j2evry9vg5jqp4dyqpwqeryu7pes4sxyyyqwn6egs5daxk4473v" +
                "9xpgzrwv5n0tvs93nlj4xpphq4vs2w8um9ph7zkte08t7fa509mnrt9apuhr22xq34mp2svjnq6rvfn0" +
                "hg6lkehxtlj39vgjxjlkjfhx8rw2f02ckq8k5szcxsnhkgr2cqlmf2udl2gqdqr5t6",
            listOf(
                2 to
                    "da2672c010f7364df6fad49dd39be0e4d4be73c45e239448fcc385cc68094bf36ddbc4ec0219b567" +
                    "955556",
                65533 to
                    "d17d19f3355bcf73cecb8cb8a5da01307152f13936a270572670dc82d39026c6cb4cd4b0f7f5aa2a" +
                    "4f5a5341ec5dd715406f2fdd2afa733f5f641c8c21862a1bafce2609d9eecfa158cfb5cd79f88008" +
                    "e315dc7d8388e76c1782fd2795d18a763624c25fa959cc97489ce75745824b77868c53239cfbdf73" +
                    "ca",
            ),
        ),
        Zip316Vector(
            "u10j2s9sy4dmuakf57z58jc5t8yuswega82jpd2hk3q62l6fsphwyjxvmvfwy8skvvvea6dnkl8l9zpj" +
                "f3m27qsav9y9nlj59hagmjf5xh0xxyqr8lymnmtjn6gzgrn04dr5s0k9k9wuxc2udzjh4llv47zm6jn6" +
                "ff0j65s54h3m6p0n9ajswrqzpvy8eh4d5pvypyc6rp5m07uwmjp4sr0upca5hl7gr4pxg45m7vlnx5r7" +
                "va4n6mfyr98twvjrhcyalwhddelnnjrkhcj0wcp5eyas2c2kcadrxyzw28vvv47q74",
            listOf(
                2 to
                    "9b728ad6f50371e961236630b3c8cdd8149ca22cdb87a62cc0ba3e3cfd2b0adcc82930e447f8dcf5" +
                    "4b450b",
                65533 to
                    "ec65604037314faaceb56218c6bd30f8374ac13386793f21a9fb80ad03bc0cda4a44946c00e1b1a1" +
                    "df0e5b87b5bece477a709649e950060591394812951e1fe3895b8cc3d14d2cf6556df6ed4b4ddd3d" +
                    "9a69f53357d7767f4f5ccbdbc596631277f8fecd08cb056b95e3025b9792fff7f244fc716269b926" +
                    "d6",
            ),
        ),
        Zip316Vector(
            "u1mtnedjgkz5ln6zzs7nrcyt8mertjundexqdxx52n2x4ww3v52s0akf3qy6sqlze3nexcjsxtcajglx" +
                "cdwg47dsrrva6g5t4nf8u3sjchhkmsqghelysrn0cl52c2m8uuv3nyfdv258jjqnvd4lgqtugc8aqvpm" +
                "t05c49qv2yqlhxvnq9phdamm4xv89cc7tzvzgmwltxxdsvme44dgzt8prkcwcsma8cdr76m8n0xwj02t" +
                "pr9086a237xakkdf8fumsj8u4r6qlf0d59x0mw83ar36vrcr94zsherapa0566vd22",
            listOf(
                2 to
                    "9dd77ff5af4c80c25114e83758cbe1b535cfe9413017994163a12b0de522cdd1b5d4be299c0788cc" +
                    "b1541e",
                65533 to
                    "2e9596fa825c6bf21aff9e68625a192440ea06828123d97884806f15fa08da52754a1095e3ff1abd" +
                    "5ce4fddfccfc3a6128aef784a64610a89d1a7099216d0814d3a2d452431c32d411ac1cce82ad0229" +
                    "407bbc48985675e3f874a4533f1d63a84dfa3e0f460fe2f57e34fbc75423c3737f5b2a0615f5722d" +
                    "b0",
            ),
        ),
        Zip316Vector(
            "u1n9znrl4zyuvds24rcapzglzapqdlax4r8rgkvek0y0xlzfjfvn7zexelrafkchea24w030cr9jqsel" +
                "7t8lvveaq7m7w4z0khmrlzc6748w9ldlccy02scd5xngtcv2yy4ctnyu9zn5m",
            listOf(
                0 to
                    "65704e3ab767ca578e5b092fb47604f659475bae",
                3 to
                    "5f09a9807a56323b263b05df368dc28391b21a64a0e1b40f9a6803b7e68f3905923f35cb01f119b2" +
                    "23f493",
            ),
        ),
        Zip316Vector(
            "u19f2knszheph2dt8lrnwqeeq9krnw39pgz8syqv028ghtg7kjz6xvu23suv5hmdmj7e6fjuu6060y34" +
                "fdw8ccjlp8gsqp0usyhrgw3reqfveet7hh2pqcjafysqqv2l3felj7sl7a7ym",
            listOf(
                0 to
                    "ef85a6553d89f153b37afcab928eb2bb5fb337db",
                3 to
                    "21006cfbb3db4f4bb63111ef63f7f80056f31b344d06aca5b7fa0740c660c8b2dc3bd234f4c18ae9" +
                    "eaf811",
            ),
        ),
        Zip316Vector(
            "u160suxvjkgt22zcp7f9xw5f0axdu7rxdt5ktyexpn4cq70w4at2f74390mns7uksfenrdmcjjzqalyf" +
                "ky6tq05jv8mnamrkyxn9dcxe35z4x6m35cczmjcj55g0fc6a2thz03sjfywxa",
            listOf(
                0 to
                    "f96a00ef8b2233236967a6a43f07ec6074f7fdc5",
                3 to
                    "04915d2bebce11111ce195226cde8440263c50204b2272ac8a96b38dbd70db8969ec9b6c87cd15d9" +
                    "d76512",
            ),
        ),
        Zip316Vector(
            "u1ddnjsdcpm36r6aq79n3s68shjweksnmwtdltrh046s8m6xcws9ygyawalxx8n6hg6vegk0wh8zjnaf" +
                "xgh6msppjsljvyt0ynece3lvm0",
            listOf(
                3 to
                    "e340636542ece1c81285ed4eab448adbb5a8c0f4d386eeff337e88e6915f6c3ec1b6ea835a88d566" +
                    "12d2bd",
            ),
        ),
        Zip316Vector(
            "u1nztelxna9h7w0vtpd2xjhxt4lpu8s9cmdl8n8vcr7actf2ny45nd07cy8cyuhuvw3axcp545y0ktq9" +
                "cezuzx84jyhex8dk4tdvwhu4dl",
            listOf(
                3 to
                    "3fadf8edb20a3301e8260aa311f4cbd54d7d6a76baac88c244b0b121c6dc22a8bcce15898e267829" +
                    "fc1e01",
            ),
        ),
        Zip316Vector(
            "u1trxzh330wl8wkh92uwv508z0qfx270ruuar8fxeng7arry5d73q9ve6gfud36s9nc4qj3uvn082l9s" +
                "rrfayjhnf20mmunywtvqzgc90c",
            listOf(
                3 to
                    "987fd74a2256c596a66f83eaff7bb026286e972be56d3b50e3459747dfba53ffa0f24732b4aa6cd4" +
                    "37a317",
            ),
        ),
        Zip316Vector(
            "u1xdrenc94696j8clxa2xnkdg8xd5t3y8s24urctyxu87vggv0u46qr4lkpnh7gqqdev9wwugt6xkv8c" +
                "8du8ufhfl8nfjnzusf6cw20wpm85hlshmnmj2lkyhka9rua7qw7kr0xeajk7y2rlsuwl6z6l5l3wq3v6" +
                "rrqt9e8zy7sc7pww45jznrj4xy6h9rp4kjy5xtl5upr30u4cyk58kv3t80k3p8w97k3e345h7avmjylx" +
                "akx6sgyk5ss8th5kqay50ewav62eeep7tghzejaflsdstpwz55haex398jqpq27007me2",
            listOf(
                2 to
                    "99ae333db1074fca1a6a94bed3ea548c1db2512dfbe75af9e84c162260b4813bb6bdb4443969daa5" +
                    "713ff1",
                3 to
                    "cdf7fed0d0822fd849cffb20a4d5ee701ad8141e66d81ddfabf87875117c05092240603c546b8dc1" +
                    "87cd8c",
                65532 to
                    "657b43ee8da645443814cc7329f3e9b4e54c236c29af3923101756d9fa4bd0f7d2ddaacb6b0f86a2" +
                    "658e0a07a05ac5b950051cd24c47a88d13d659ba2a46ca1830816d09cd7646f76f716abec5de",
            ),
        ),
        Zip316Vector(
            "u1y647tzm2ms4stj8skfswfljmvatmhwqzjzl2uq5v3a78ys2mls2g9thdap4yfmr9tw6y5h9gnehzhp" +
                "ddyl43enmhd6xv2udcttqmas35l62jt2yar33jwr5eulchzxg3d8upf2raqcx3jup8s3dep6an5n5xh9" +
                "ngdjfp4hjv8fwfhh34kvglsug57zf0duypq6ugmysw0mnhdg5fz9sndputdc7pdssg6k3ks76wrrnuu5" +
                "najqxj8xchp5xv5ahfh3f2szrfl5cm6mslq2f69ja9r54plen209xwpdpwsvm6zep4gwl",
            listOf(
                2 to
                    "52d58f91376aa980f2b9a6283ff357e84246d6942352184886449ffea8fad7e7ca5b490d090a96e0" +
                    "323392",
                3 to
                    "e4e01051b99c08506834971f80dadec44a4da13ecdcba617f77fc48d25324f57cb1d4d7424705d57" +
                    "3cd682",
                65532 to
                    "07fe9b523410806ea6f288f8736c23357c85f45791e1708029d9824d90704607f387a03e49bf9836" +
                    "574431345a7877efaa8a08e73081ef8d62cb780ab6883a50a0d470190dfba10a857f82842d38",
            ),
        ),
        Zip316Vector(
            "u1m5jvynaxyrtk27mt23q0j4r8uf5dzzhlwf6qd4s7pfdclqnmgkaf82kqrch0p44kd97f9pmwnk6q3r" +
                "njnzvlwv2ll289ahzlee4zcnual03ntelg2q2wxlqc6ueav935j4j2rzv2gxcdh6lk67quzxnxt5ay9x" +
                "h0qjc9575dptfs9luhhr0m9wms2taq2vnrryjdj3ht5cktwathcerl9kw25y89f3hffyr65rnfw0jk2k" +
                "a7703m8wym0c04u6r0xgagpn7xzfaxttrwgftmztzln6y2qcdglk3u28dgrswywqne28g",
            listOf(
                2 to
                    "7a53f581d15985d0aafe134adb9540ffd19d965a43977ed4cb552cac5740a907ea24a9152b52268f" +
                    "e8cc3f",
                3 to
                    "b5a053ec1ab0623ce04f350cbb26031338dea9074551433adeb1bf3cb67c1e93982f42de822ebe42" +
                    "99692a",
                65532 to
                    "25b3d6da0573d316eb160dc0b716c48fbd467f75b780149ae8808f4e68f50c0536acddf6f1aeab01" +
                    "6b6bc1ec144b4e553acfd670f77e755fc88e0677e31ba459b44e307768958fe3789d41c2b1ff",
            ),
        ),
        Zip316Vector(
            "u1tqx832p4wsfe9pd67ggm3qsmfuvdhqvw2259y7uwug7y0lpeu87fmgpqh3zmamex3fzs0d4ct4hhsg" +
                "2csj5z0q5f3f7n656ap8e4nlng9c4440rz9s7ekxanfw6g84f7vu82fumtmlz3vstl2a9ufa0970k4kn" +
                "sz2wpsjt2xycqeay76pt4fx3ak9y7mps2q6qe2n2h7wkakxr7xu6vd36zhhzgln7ttmrzc0f9ye3jmyu" +
                "2pp8l8rect87lfxj2fgckcwz3svdx70a947fz04kgu7e907enzrk676zdkdmuyw2kyrclkmj62kmyy2r" +
                "jetpus7knmxfuu7z0m63uwfhdynhuu3yrjqu5y089v8zwnh60mw5ngc0kszdjmc339fk9mjn396m5ekv" +
                "7h7td7fa0u9097xph3y5vth9af4sw6ykxdms84wr544mxxqtmgj027d9e8rnlrazge0kwyydyhder3ch" +
                "whmaqjk9skuxgxzternw4xx962qed",
            listOf(
                0 to
                    "294dbb37edd92ce046e266e22b0ed530a44b79c7",
                3 to
                    "24fd59f32b2d39dde66e46c39206a31bc04fa5c6847976ea6bbd3163ee14f58f584acc131479ea55" +
                    "8d3f84",
                65531 to
                    "2fe806b94569cd4059f396bf29b99d0a40e5e1711ca944f72d436a102fca4b97693da0b086fe9d2e" +
                    "7162470d02e0f05d4bec9512bfb3f38327296efaa74328b118c27402c70c3a90b49ad4bbc68e37c0" +
                    "aa7d9b3fe17799d73b841e751713a02943905aae0803fd69442eb7681ec2a05600054e92eed55502" +
                    "8f21b6a155268a2dd6640a69301a52a38d4d9f9f957ae35af7167118141ce4c9be0a6a492fe79f15" +
                    "81a155fa3a2b9dafd82e650b386ad3a08cb6b83131ac300b0846354a7eef9c410e4b62c47c542690" +
                    "7dfc6685c5c99b7141ac626ab4761fd3f41e728e1a28f89db89f",
            ),
        ),
        Zip316Vector(
            "u1adph5ua2pv8ghr7utshst0fm0ad7tj32y09t2nhxn2ccwm6hengck3w2vy34tvhqay7rlw8vcfh63f" +
                "85lh7lz63l0c5vja49tu8vcxvx30re085n8jt5hcqh4g4ec77czl4c8nspqps2ac2g5kxhl4j5g6mz3v" +
                "svxrg74e8p9s8hhqu8u3gldhxvrxg2htykqc7ceh930f3edxsg49nctv2e36cne6qpkvxzfymh2el2eg" +
                "uw6kg7zvdu620rgk4cwyvt9hz7zpjk9wskjdpk6p3cpyx3yuf5lk46nx2fyqjca3vtz8d9df3tpmg74d" +
                "90uv7pp09apfa5ep374clznmh2ne5suxtzk22cp7mvu9gtswpvx9wfst63s73yjwqu9cjenwntdsep0u" +
                "qz2hgnh4xpq0rlllwgv8z70ke6z5zkwnjmlrzt6nsvhac4zz245rp3rkj9lmj8tvpfmd0zawy08dv3hq" +
                "xxf8cr06x90amtgkh2ura0yyfwucu",
            listOf(
                0 to
                    "6462c9a3003e4d0e0ab764860d8b71f8a36a23ff",
                3 to
                    "933bf1eb8fc99c38251bd42bb2e7e4afe526352a9b024f8d671b4d337277194b52338a91ce472503" +
                    "a48a00",
                65531 to
                    "fdeca364dd2f0f0739f0534556483199c71f189341ac9b78a269164206a0ea1ce73bfb2a942e7370" +
                    "b247c046f8e75ef8e3f8bd821cf577491864e20e6d08fd2e32b555c92c661f19588b72a89599710a" +
                    "88061253ca285b6304b37da2b5294f5cb354a894322848ccbdc7c2545b7da568afac87ffa005c312" +
                    "241c2d57f4b45d6419f0d2e2c5af33ae243785b325cdab95404fc7aed70525cddb41872cfcc214b1" +
                    "3232edc78609753dbff930eb0dc156612b9cb434bc4b693392deb87c530435312edcedc6a9611333" +
                    "38d786c4a3e103f60110a16b1337129704bf4754ff6ba9fbe659",
            ),
        ),
        Zip316Vector(
            "u12acx92vw49jek4lwwnjtzm0cssn2wxfneu7ryj4amd8kvnhahdrq0htsnrwhqvl92yg92yut5jvgyg" +
                "k0rqfs4lgthtycsewc4t57jyjn9p2g6ffxek9rdg48xe5kr37hxxh86zxh2ef0u2lu22n25xaf3a45as" +
                "6mtxxlqe37r75mndzu9z2fe4h77m35c5mrzf4uqru3fjs39ednvw9ay8nf9r8g9jx8rgj50mj098exdy" +
                "q803hmqsek3dwlnz4g5whc88mkvvjnfmjldjs9hm8rx89ctn5wxcc2e05rcz7m955zc7trfm07gr7ank" +
                "f96jxwwfcqppmdefj8gc6508gep8ndrml34rdpk9tpvwzgdcv7lk2d70uh5jqacrpk6zsety33qcc554" +
                "r3cls4ajktg03d9fye6exk8gnve562yadzsfmfh9d7v6ctl5ufm9ewpr6se25c47huk4fh2hakkwerkd" +
                "d2yy3093snsgree5lt6smejfvse8v",
            listOf(
                0 to
                    "d5bca9e4e50be0c16bdfed7e5aca20ad43a23f20",
                3 to
                    "5ef2817381571b0e85215959f1fad87bf99bfb0799d81b2824fe4cc10f07776bbe7305e7c4c3933b" +
                    "e4270f",
                65531 to
                    "51e610620f71cda8fc877625f2c5bb04cbe1228b1e886f4050afd8fe94e97d2e9e85c6bb748c0042" +
                    "d3249abb1342bb0eebf62058bf3de080d94611a3750915b5dc6c0b3899d41222bace760ee9c8818d" +
                    "ed599e34c56d7372af1eb86852f2a732104bdb750739de6c2c6e0f9eb7cb17f1942bfc9f4fd6ebb6" +
                    "b4cdd4da2bca26fac4578e9f543405acc7d86ff59158bd0cba3aef6f4a8472d144d99f8b8d1dedaa" +
                    "9077d4f01d4bb27bbe31d88fbefac3dcd4797563a26b1d61fcd9a464ab21ed550fe6fa09695ba0b2" +
                    "f10eea6468cc6e20a66f826e3d14c5006f0563887f5e1289be1b",
            ),
        ),
        Zip316Vector(
            "u1uehkuaq6rpfgt4ed5zpvhczg9apgpmyk5eq9qg23j8w7jxkhdnqzacte6gu8zgzfzgxy48ryzus3wn" +
                "khfxrxmlhs34xde3f34uxcnv3y6dsgj288vu56xs9f6ghvqsgkhuwtz4kkfxj8pa27v5p3ttlst340zv" +
                "wx9nj6s0zw8p3wwk3zh37dwc7znqz52gj2fpaapzxzyagah0aeyxwa9fxxvyyj6w989v96ymsgf7s8s6" +
                "ej9346p60fcjzzynvf9rmxevumdvt8l9mvhdfz4u5j4h7e0zjr2sde7fu7z9s02447qg6qzllm22egnx" +
                "6ej6qczkkk2ygvpy08un9ggp853sddp6vskrlar6sygxec5f6c2t2eu9zmc728esy4sj9z853gxuplr6" +
                "hw7lpcwzk20d85vuflnhlfv8nr3020r0v9z83ryudsyjv66rttxq2cscqlrdxakrmpjptzcf",
            listOf(
                3 to
                    "6ed96d65379d5ece656901f5cb20cf554ce18600d4a1edcf6812f4459d7ff73cf2b88cd8476b75e8" +
                    "c08d28",
                65535 to
                    "34d6e84bf59c1e04619a7c23a996941d889e4622a9b9b1d59d5e319094318cd405ba27b7e2c08476" +
                    "2d31453ec4549a4d97729d033460fcf89d6494f2ffd789e98082ea5ce9534b3acd60fe49e37e4f66" +
                    "6931677319ed89f85588741b3128901a93bd78e4be0225a9e2692c77c969ed0176bdf9555948cbd5" +
                    "a332d045de6ba6bf4490adfe7444cd467a09075417fcc0062e49f008c51ad4227439c1b4476ccd8e" +
                    "97862dab7be1e8d399c05ef27c6e22ee273e15786e394c8f1be31682a30147963ac8da8d41d80425" +
                    "8426a3f70289b8ad19d8de13be4eebe3bd4c8a6f55d6e0c373",
            ),
        ),
        Zip316Vector(
            "u1m76hh3wch9vwctg92h0jjt8zu6dry4zl97q9q94huutng5sxyhlzgfj64jqnvla2vqrqe0ndt67td2" +
                "kejv6zlcw9zeurexxs67l7y67p7mww2j2uvfsp6uynct2apcr0m9xrmswtktmgs3x2glvndrqazy0gyr" +
                "p30j328h4m5gkju9rl3pfrtjn9tm8v0rzr6t8gkklqfxgwk976dvv4kh7hl5utp9gjryu8wwu80h733s" +
                "s5cjwpeewdgd3l8h46c0c7hxz4c6daws3vurq2fj9h0hpjnycup9tu8nfahvqjxewyhyuzynnjxa7jrv" +
                "w2ekdytqs7sn02gqx4vxtkjzfrcy67lkmr6p5kalj0g8apazeyzqw3ywppy9482wj8k4tm06573nr3h7" +
                "8ecq9n260g7c0hm5jm3ffa4g2vk0edpdsnemksdegxgt9s7h8v8pjmcp23rnahmzf8pxdtdt",
            listOf(
                3 to
                    "b6f481042a780462ffa96f81e1288978e5f05c791587de7e957729bcac6eb95892532b0fe13e9c7e" +
                    "ef6a24",
                65535 to
                    "d456851879f5fbc282db9e134806bff71e11bc33ab75dd6ca067fb73a043b646a7cf39cab4928386" +
                    "786d2f24141ee120fdc34d6764eafc66880ee0204f53cc1167ed20b43a52dea3ca7cff8ef35cd8e6" +
                    "d7c111a68ef44bcd0c1513ad47ca61c659cc5d325b440f6b9f59aff66879bb6688fd2859362b182f" +
                    "207b3175961f6411a493bffd048e7d0d87d82fe6f990a2b0a25f5aa0111a6e68f37bf6f3ac2d26b8" +
                    "4686e569d58d99c1383597fad81193c4c1b16e6a90e2d507cdfe6fbdaa86163e9cf5de3100fbca7e" +
                    "8da047b090db9f37952fbfee76af61668190bd52ed490e677b",
            ),
        ),
        Zip316Vector(
            "u1c2tpmmdl49pdcfntc2e2gjaxmj2a0ackydlj9aeuqlet4erjdn2edwvtx6vd8nrkxjnvgckn4j3nx4" +
                "8p2gep5x23akrl2cv7u2un4vmjed9hav39taqgzyp602m3tpcv3uzdsjdyl8wxrjycx5aus8ypq2xja8" +
                "yw0cf045n0zvwt3ajtgs2xyzjl6cq2245avkm26qjv72ta65h04etlp4ntdq87eu9efjx5v6gjsfvwrd" +
                "t99m4lpu9j52t0h8yvpnzukuzdt89e3pg9cmderzh7tnahmw0rfyc37aqmd6dh24fnxmxagsj4mtz8jv" +
                "3c3ch20xu4k6whwfsaf2sra4ktgdej9p6kqz05ae3vl3f93xsfx05xpaf884h56epcetx627jttgx249" +
                "9vc0uzxl83hcdt92z4hy5la40ervrpha4kn3kxxwrngdj76u6mrfcmt4737czn08vd60k5gj",
            listOf(
                3 to
                    "a8e557a58a1908eb8a1bb078b77a95c032fe0a0069ce8c89d3e7705a48d2c08f7b604e5af0218d8c" +
                    "c9c8b8",
                65535 to
                    "515d014384af07219c7c0ee7fc7bfc79f325644e4df4c0d7db08e9f0bd024943c705abff8994bfa6" +
                    "05cfbc7ed746a7d3f7c37d9e8bdc433b7d79e08a12f738a8f0dbddfef2f2657ef3e47d1b0fd11e6a" +
                    "13311fb799c79c641d9da43b33e7ad012e28255398789262275f1175be8462c01491c4d842406d0e" +
                    "c4282c9526174a09878fe8fdde33a29604e5e5e7b2a025d6650b97dbb52befb59b1d30a57433b0a3" +
                    "51474444099daa371046613260cf3354cfcdada663ece824ffd7e44393886a86165ddddf2b4c4177" +
                    "3554c86995269408b11e6737a4c447586f69173446d8e48bf8",
            ),
        ),
        Zip316Vector(
            "u1dqavtnjvu42hlsjw6sc2mxajqlyt03zg8l4luykz9fnchunq74nqxhfp58h5n5xfpyqhheax8thta8" +
                "lfkjgp8wqwsavc0g4mgu4du02c",
            listOf(
                3 to
                    "5178924f7067eac261044ca27ba3cf52f798486973af0795e61587aa1b1ecad333dc520497edc61d" +
                    "f88980",
            ),
        ),
        Zip316Vector(
            "u1q8g29qhrktunc24lud3fgk007u7ya8q5g8vy9awadxtl7wu5vjllr4mmdfwk0zdh8zqxgl93sthzum" +
                "eanzzkdqmqdft6ryhwtvqyqt3e",
            listOf(
                3 to
                    "907639193311a847366c1a43ebaadd935a53180fd3e1219c07c8205f45077bc1768abdcf2425a4a1" +
                    "3c4aba",
            ),
        ),
        Zip316Vector(
            "u13j3q8q8f9hx2nx0w9l52dqksy4png7fgm0lqjh8ahn9enyvz5z9xnwzdcdjmpf756s2y88rnyr9px4" +
                "f4k9w03sl6fr4vwsqcvg8ggfjx",
            listOf(
                3 to
                    "2809ddfc7db70c660a6c3fc7560c7add1c7889d9b277cb92d14cb40d2de00aae31670b753a42bdcd" +
                    "c3c220",
            ),
        ),
        Zip316Vector(
            "u1ukslldhknrzmvpdmn03u03edgfy976w3muurfs9asvh3n9uh9h6sgle6m7yjgf3wafxtvke08u735v" +
                "4nd3kjqnyulw7cvxh6ke357knyjudgqtes6kcw7y28e6kewr03pjah5mh26na",
            listOf(
                0 to
                    "e8225b817cdcfd01307c66ca35188e9b1ac238ca",
                3 to
                    "b208c9235c8d40e49b76100b2d010f3783f12c66e7d3beb117b2c96321b7f6562adb4efc144e39d9" +
                    "09e728",
            ),
        ),
        Zip316Vector(
            "u1a0dnfvgdp4khm5yk79ltkkvp8jjmjykjy38cdue8ktl8askwenl4lzfyu0p7end0guyu6up57wylzn" +
                "s0tpr99wz5z8edh5u0m4yzuusysr3d2xczwkp82atq3vfw45u2yvtau852lnw",
            listOf(
                0 to
                    "3869048bd22a3c3e6bc884333b0a71b05f7f4125",
                3 to
                    "332f451dc6f7da17fe5ff4077d3d5db79a036e712df558853d4a854ac4f6e51474cf75f38fa97c22" +
                    "b4cf09",
            ),
        ),
        Zip316Vector(
            "u1a84vn0qes8q3jhk7zxs2whd2p922far8kztqdapergs5ej8rarn53v5ddnd6t7e3l5efhaefrhkpta" +
                "tnzq565nrpvf7kn2787gdvervmk08azp4qgehaew2zplkxkkyu36l3v7drg2v",
            listOf(
                0 to
                    "59e919ce60110f97707c5c232b7d4db19e32c3ed",
                3 to
                    "3b68c29b4a138b289fea8b6795e64759a7cd7c0aaf4bb98ed3079959b0bba9b761704b6cfc1465ad" +
                    "74bb05",
            ),
        ),
        Zip316Vector(
            "u187vrwl4ampyxd5m6aj38n4ndkmj8v6gs97hkt23aps3sn5k89a0gk2smluexgdprcrtm56ezc5c7tj" +
                "wlrnnl79tjtrxmqd42c5mpyz7g",
            listOf(
                2 to
                    "eee19641bc6b802f353eb793f728b17a277ef0358696a24a7122bc56537b229647f3810d27ce4522" +
                    "7c6f39",
            ),
        ),
        Zip316Vector(
            "u1xd83nhheggwe78x3lvcygdl8cmwz3gfxnr02sytkxvfpwdep9dzl7vte48zhkx39s705yqp20rw4l8" +
                "35fhg3ylkde44l7glt3cyps5wk",
            listOf(
                2 to
                    "50ca46f825f7f423007aa4147169b529f07f1c8ed634fafc8145a4813177dd1257ee8d8fc5f44e9b" +
                    "564f6a",
            ),
        ),
        Zip316Vector(
            "u1w7x9ttwvk30grems6ae3rhgs6xytrrueaklyc5t509fpux7043fzla70jehhxyn4mg9d3ym095s3wg" +
                "hl9trvvdmu56yn74ajqy38ufjg",
            listOf(
                2 to
                    "c412c8ff78f28d9b3391f4ab15d06acf46ac052821ee096a51524813f2adf9a4065cc6c45feba2c0" +
                    "52df9e",
            ),
        ),
        Zip316Vector(
            "u1smpx6drvevct3dyrer7esjlct99lf4nxdeltdetyxjdrmtqag7q7mkrd8rxlvj9e5vy0qy24fhvvvr" +
                "j7agfdgxapefxe72xl8vuu9ds5yfq0p86r3y0jw4suurzjz5s6lzrxkfft4am",
            listOf(
                0 to
                    "f441228ee26a3a7d0d00e4d65ba49e3aa4877eb8",
                3 to
                    "2598d84dffb34f5908b90732490f3881399150d4c694fce9bf30d1560b2c56f09829fe123b9add20" +
                    "e5d71c",
            ),
        ),
        Zip316Vector(
            "u1ymxkv9nks7tuzjt265fg8vctdq5nxqw4l0q2xj2ya5dkt660rrzkg032v5duhgeqae6cnh9tzxry4d" +
                "spv8yvtq5lem9gujysaz64034mavd8p0ejqhnvp2jg34nt24y2c2whclxxk94",
            listOf(
                0 to
                    "9f1f8526792b04efdda3b38981867397ac11e3c0",
                3 to
                    "c1150ae8529e667015c462f91fb26e9124095aebd6e72fca95a2fe17ae53e8cb101eda84d9fb4d33" +
                    "6ee103",
            ),
        ),
        Zip316Vector(
            "u14j8rtl62a70skh0nhzv7tasxsa69axm0vlac37ye3mcgfpjk6k9ury7hlmet0grhvhedtfj27xmsyg" +
                "p06pcm932f8sc33u5uwps57d89667kyhwmj8pucp5r8cel2lhuaxmx5ftm2nt",
            listOf(
                0 to
                    "6149d0373c63fddd4fca3b9f5407ad22abda0df2",
                3 to
                    "e961944a708a15c9c62734c34510bb5e2cd740abdeb488e4142b5d402b0295bec67922f1e71ab7fb" +
                    "d0a2ae",
            ),
        ),
        Zip316Vector(
            "u1xjkw3lwwf9crx8cz050gdwfejufzhcusc37ged99w8fyj7tyx3e7hgmauyuv538dak2sepq6wjv4ty" +
                "yjnhcef02dr682y5dsuzuftsx83lrvfc6dxd0kk260m4p3c9ka96vf3z9u6axvsj47mfd6kszy39e5gm" +
                "a28yg88yp92kxjt8ah0x329j4gxjdfyn0n2wp3urwrxxz6z0ynx82",
            listOf(
                0 to
                    "0eb9651c003776ab5d1e93c2779d10a0bdc3bb77",
                2 to
                    "d3a803803feee7a032a24adfaa8f6a94cecb9671c1333d0d5d1a3d79d82bc310727c665364d71022" +
                    "559c50",
                3 to
                    "7c98b8f613f9ff02746bea2a167cfd1bd3a1862af9631bf61d9d604e0824e2cb8467a1e549db87a7" +
                    "6e7a8a",
            ),
        ),
        Zip316Vector(
            "u1p4c4u3uz2vtkedv78d4phjav86exankz0x9wmrmz8q4mxqaf43gwd0qt486jk5jvpvyccc6lyy2vaq" +
                "3ht8ngnw4vusryxd9erhhl2uy5x6x4huyfdymwxj7dkyyeut8ld36kxwu3v5wjg7jwp9kr8ul7u3xdak" +
                "funvmwq0rkv6y4k0ngm2n24x763uurfmrr685welsefyys2xwp8ug",
            listOf(
                0 to
                    "69f48a4974e80758ed435592a1dd4e4b38826cbc",
                2 to
                    "25c25d58c50533dfb55d29f9a8864f58f02ea4fed44369352c43538cdf9545b905bb2ef0961bd2da" +
                    "f25883",
                3 to
                    "8a1bff2a9d921e1153b3cb264bc05185a9811de911d53467935434d6537d306752d02054fe5a1704" +
                    "64259d",
            ),
        ),
        Zip316Vector(
            "u1ap7zakdnuefrgdglr334cw62hnqjkhr65t7tketyym0amkhdvyedpucuyxwu9z2te5vp0jf75jgsm3" +
                "6d7r09h6z3qe5rkgd8y28er6fz8z5rckspevxnx4y9wfk49njpcujh5gle7mfan90m9tt9a2gltyh8hx" +
                "27cwt7h6u8ndmzhtk8qrq8hjytnakjqm0n658llh4z0277cyl2rcu",
            listOf(
                0 to
                    "f1bc3d7261bf77fe808e2b7178981c7cfe5570fd",
                2 to
                    "54b7fc0c85d378f375be48218a85424bb9e7a304830e9eb7255a12a09c961cca1f629b867e13242e" +
                    "d90d92",
                3 to
                    "14adca6f616abcbe5bc850cc617dcf999517a9a790292fec6bc0761eaa790333e7d06d016de05bca" +
                    "7c6712",
            ),
        ),
        Zip316Vector(
            "u1udmzarqn6y9026whk083lm5vs8pv282egeln6xg0n2a3w4klkpn6208h68ntuus7gp54d937u4f724" +
                "v2xgdx6qeu74j45vxfn822xty2yyx6u0ecakj8r9uu3r2jqafj64w7updkhtq",
            listOf(
                0 to
                    "407158fc804361fcb965dfa4882f0f1df5a49f47",
                3 to
                    "a80405d5568ab8ab8f8546163d951ab297fd5e6f43e7fcebcb664feacfab5afd80aaf7f354c07a99" +
                    "01788c",
            ),
        ),
        Zip316Vector(
            "u1fyvdgdehrx3gvjx5f2ez2lkcm0lcrfxg8hksdmg3g8zujfz8xk2kyhu4dafs99y96sq2t5c3d3zsxh" +
                "hnlfmj6trmttg5awtwczz8g8xjr7u30hxc4nkyfyefyl4xt3dxdjevsnrkqdg",
            listOf(
                0 to
                    "f597980d65ca2ecd0fab5354e66ba9d4cd50f463",
                3 to
                    "33112cb923b3197a38c7a6eb50a837b0a44952fe31e528a1512994fcfa2b5f87b9c86ed9234426d3" +
                    "bbb526",
            ),
        ),
        Zip316Vector(
            "u1kfzux4hf9favh8jmssqa2h04k87advldqz5ze7a8t4un3nkegklhz3ewzk6lmqg0uy7matdway9vn2" +
                "q8q9rxp0fjwuewpcjtwrwavxjdsfxdvsk5nkx4q35atp0tfepfdsapqkk4en5",
            listOf(
                0 to
                    "3e7f16836d93b5417445ad0fc9f7ba023617e2b3",
                3 to
                    "0dacf7b768abb04a02b2e30bf31440300b64275f3677d02e52ba0f4ce779cafee8ceea69acf2e1f0" +
                    "fee926",
            ),
        ),
        Zip316Vector(
            "u1hrwrtyl3m8m2c6vkhu8wng43j5yvwweg37n2qstsqwc9dfw4vhs69m09064522758p44pfz42gu6hy" +
                "djxua0wt0ge907sgrxkc9mft4gyfjevkhsyl4d8lnzgyd90arhx4t6v20zlfz",
            listOf(
                0 to
                    "29b06b228eb6b70fda051ff9e01bcb271b51c683",
                2 to
                    "8660070e3757ff6507060791fd694f6a631b8495a2b74ffa39236cf653caea5575b86af3200b010e" +
                    "513bab",
            ),
        ),
        Zip316Vector(
            "u1rf4n5f682jspygln8r5pjwh6fmta7xz6n9x868f5wgc9prxsqkrh8jkpmn7wfnag56ml7czw68dv96" +
                "299ft6s98p05u4jvdx3elyr83jqnzr603vw8yarptpg5pj73zlea0sksuje3r",
            listOf(
                0 to
                    "29099a651d5561f800e58f3e33c27f078a98581f",
                2 to
                    "6d75a1a948a4e730db3b4b816dbc7d80b4eb1bc68de9ac87b0cd1f1b3e6068e677888e105ac727c0" +
                    "d14b49",
            ),
        ),
        Zip316Vector(
            "u1l6exm3zmfsr74sqvlwgc0zf7mydwf6z5r79amka84kfwzwef3wxs0yupl2lwhws85vdmqet3rtz795" +
                "gpnm4h0jjfv4hanwqta0ezlxqe4p578a4aq09s93xhhtf3xhtrlh575qrsf5g",
            listOf(
                0 to
                    "475494432c3437d50ef23623cb67670fef27d8f5",
                2 to
                    "38b14b44ed6f4a3ae8c5c3923e5770b786f9b41d46c65a149b13910f4a0a64e83bb9bc98e80d9576" +
                    "fbf76e",
            ),
        ),
    )

/** normal to jumbled, in hex. */
internal val F4JUMBLE_VECTORS: List<Pair<String, String>> =
    listOf(
        Pair(
            "5d7a8f739a2d9e945b0ce152a8049e294c4d6e66b164939daffa2ef6ee6921481cdd86b3cc4318d9" +
                "614fc820905d042b",
            "0304d029141b995da5387c125970673504d6c764d91ea6c082123770c7139ccd88ee27368cd0c092" +
                "1a0444c8e5858d22",
        ),
        Pair(
            "b1ef9ca3f24988c7b3534201cfb1cd8dbf69b8250c18ef41294ca97993db546c1fe01f7e9c8e36d6" +
                "a5e29d4e30a73594bf5098421c69378af1e40f64e125946f",
            "5271fa3321f3adbcfb075196883d542b438ec6339176537daf859841fe6a56222bff76d1662b5509" +
                "a9e1079e446eeedd2e683c31aae3ee1851d7954328526be1",
        ),
        Pair(
            "62c2fa7b2fecbcb64b6968912a6381ce3dc166d56a1d62f5a8d7551db5fd9313e8c7203d996af7d4" +
                "77083756d59af80d06a745f44ab023752cb5b406ed8985e18130ab33362697b0e4e4c763ccb8f676" +
                "495c222f7fba1e31defa3d5a57efc2e1e9b01a035587d5fb1a38e01d94903d3c3e0ad3360c1d3710" +
                "acd20b183e31d49f",
            "498cf1b1ba6f4577effe64151d67469adc30acc325e326207e7d78487085b4162669f82f02f9774c" +
                "0cc26ae6e1a76f1e266c6a9a8a2f4ffe8d2d676b1ed71cc47195a3f19208998f7d8cdfc0b74d2a96" +
                "364d733a62b4273c77d9828aa1fa061588a7c4c88dd3d3dde02239557acfaad35c55854f4541e1a1" +
                "b3bc8c17076e7316",
        ),
        Pair(
            "25c9a138f49b1a537edcf04be34a9851a7af9db6990ed83dd64af3597c04323ea51b0052ad8084a8" +
                "b9da948d320dadd64f5431e61ddf658d24ae67c22c8d1309131fc00fe7f235734276d38d47f1e191" +
                "e00c7a1d48af046827591e9733a97fa6b679f3dc601d008285edcbdae69ce8fc1be4aac00ff2711e" +
                "bd931de518856878f7",
            "7508a3a146714f229db91b543e240633ed57853f6451c9db6d64c6e86af1b88b28704f608582c53c" +
                "51ce7d5b8548827a971d2b98d41b7f6258655902440cd66ee11e84dbfac7d2a43696fd0468810a3d" +
                "9637c3fa58e7d2d341ef250fa09b9fb71a78a41d389370138a55ea58fcde779d714a04e0d30e61dc" +
                "2d8be0da61cd684509",
        ),
        Pair(
            "3476f21a482ec9378365c8f7393c94e2885315eb4671098b79535e790fe53e29fef2b3766697ac32" +
                "b4f473f468a008e72389fc03880d780cb07fcfaabe3f1a84b27db59a4a153d882d2b2103596555ed" +
                "9494c6ac893c49723833ec8926c1039586a7afcf4a0d9c731e985d99589c8bb838e8aaf745533ed9" +
                "e8ae3a1cd074a51a20da8aba18d1dbebbc862ded42435e92476930d069896cff30eb414f727b89e0" +
                "01afa2fb8dc3436d75a4a6f26572504b192232ecb9f0c02411e52596bc5e9045",
            "5139912fe8b95492c12731995a0f4478dbeb81ec36653a21bc80d673f3c6a0feef70b6c566f9d34b" +
                "b726c098648382d105afb19b2b8486b73cbd47a17a0d2d1fd593b14bb9826c5d114b850c6f0cf308" +
                "3a6f61e38e42713a37ef7997ebd2b376c8a410d797b3932e5a6e39e726b2894ce79604b4ae3c00ac" +
                "aea3be2c1dfe697fa644755102cf9ad78794d0594585494fe38ab56fa6ef3271a68a33481015adf3" +
                "944c115311421a7dc3ce73ef2abf47e18a6aca7f9dd25a85ce8dbd6f1ad89c8d",
        ),
        Pair(
            "7e745939ffedbd12863ce71a02af117d417adb3d15cc54dcb1fce467500c6b8fb86b12b56da9c382" +
                "857deecc40a98d5f2935395ee4762dd21afdbb5d47fa9a6dd984d567db2857b927b7fae2db587105" +
                "415d4642789d38f50b8dbcc129cab3d17d19f3355bcf73cecb8cb8a5da01307152f13936a2705726" +
                "70dc82d39026c6cb4cd4b0f7f5aa2a4f5a5341ec5dd715406f2fdd2afa733f5f641c8c21862a1baf" +
                "ce2609d9eecfa158cfb5cd79f88008e315dc7d8388e76c1782fd2795d18a763624",
            "1a52585e652da6ea46994954905cb79f55fca58171a4d7f773a57d23ed9ddec0c745ef0f4588fa7b" +
                "2b68d69cdd25e5eb0e08c20523a3957171f1730ab0636faee75da2dc9e89562f0653d4e942217928" +
                "6ae8305f01371f47ab16eed692c3895ce2fd655e4b19651c35d83c81894f687055b5811144406465" +
                "08e39a49b0d5a99004560af7367cc2738344d4e797a995ed66df72228e3d3746674337104700144c" +
                "73b6db27d238c9e1770662feb0957d5028b5086f3839aacf275022dd7e7e983b6d",
        ),
    )

/** message length to BLAKE2b-512 of the jumbled message (hex). */
internal val F4JUMBLE_LONG_VECTORS: List<Pair<Int, String>> =
    listOf(
        3_246_395 to
            "3fc2ecdfb68696571d89e8beddb647e6990b63a0171c36442273d687bd99257e" +
            "c5002ec8197801b621732d6b05b8d70f688620a4c08873c12e4439a0127dc945",
        4_194_368 to
            "a5f18f163e598d4adb6ea7248057e24c1b61f29b33b7abcdabd420a0f2ee6c3e" +
            "d31394652f28b59c44d3ea9ecf85f4d501e6aac14df288efd62cf80d1829d025",
    )

internal class Blake2bVector(
    val outputLength: Int,
    val personal: String,
    val message: String,
    val digest: String,
)

internal val BLAKE2B_PERSONAL_VECTORS: List<Blake2bVector> =
    listOf(
        Blake2bVector(
            64,
            "55415f46344a756d626c655f47000000",
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f2021222324252627" +
                "28292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f",
            "20484ac8c162f1d77f61301c2e9bb5344e22b42988962e99cc5e4b486e2f94942643e401bd522687" +
                "0dfd96683f935f0d7e3f8e11ef51c5eb60d0a13e670a8459",
        ),
        Blake2bVector(
            64,
            "55415f46344a756d626c655f47010001",
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f2021222324",
            "32f2e9af30736d46d2517a090ba4b68f9a29e0c2dbb5a3c344a9d6073795d2699bd0f3fe540b3542" +
                "82bdf2e554c32bf0afedd1aac6b50add0eec730898e2247b",
        ),
        Blake2bVector(
            48,
            "55415f46344a756d626c655f48000000",
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f2021222324252627" +
                "28292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f404142434445464748494a4b4c4d4e4f" +
                "505152535455565758595a5b5c5d5e5f606162636465666768696a6b6c6d6e6f7071727374757677" +
                "78797a7b7c7d7e7f808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f" +
                "a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebfc0c1c2c3c4c5c6c7",
            "ab18dfcca7cebfc5580807e2aa46a4d9dec9a384d4e43d52e60164e433154815ae001665203771fa" +
                "9cbc3926f8cf750f",
        ),
        Blake2bVector(
            21,
            "55415f46344a756d626c655f48010000",
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f2021222324252627" +
                "28292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f404142434445464748494a4b4c4d4e4f" +
                "505152535455565758595a5b5c5d5e5f606162636465666768696a6b6c6d6e6f7071727374757677" +
                "78797a7b7c7d7e7f80",
            "10fbf21b902fa82616e9143546adefd60b20c2c25f",
        ),
        Blake2bVector(
            64,
            "55415f46344a756d626c655f47000201",
            "",
            "3389c26d54762e100f3e7f3ec342239898be0d521dccb4a36d67419483898c3a1cdb8a09960b52b3" +
                "8229a1283e65b5d7a85e8916e50d1e09424caccabdbbb815",
        ),
    )
